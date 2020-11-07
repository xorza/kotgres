package postgres.json.parser

import com.sun.tools.javac.code.Symbol
import kotlinx.metadata.Flag
import kotlinx.metadata.KmClassifier
import kotlinx.metadata.KmType
import kotlinx.metadata.jvm.KotlinClassHeader
import kotlinx.metadata.jvm.KotlinClassMetadata
import postgres.json.lib.Column
import postgres.json.lib.Id
import postgres.json.lib.PostgresRepository
import postgres.json.lib.Table
import postgres.json.lib.Where
import postgres.json.model.db.PostgresType
import postgres.json.model.klass.Field
import postgres.json.model.klass.FunctionParameter
import postgres.json.model.klass.Klass
import postgres.json.model.klass.KlassFunction
import postgres.json.model.klass.Nullability
import postgres.json.model.klass.QualifiedName
import postgres.json.model.klass.Type
import javax.annotation.processing.ProcessingEnvironment
import javax.annotation.processing.RoundEnvironment
import javax.lang.model.element.Element

class Parser(
    roundEnv: RoundEnvironment,
    private val processingEnv: ProcessingEnvironment,
) {
    private val elementsByName: Map<QualifiedName, Element> = roundEnv.rootElements
        .associateBy {
            QualifiedName(
                pkg = processingEnv.elementUtils.getPackageOf(it).qualifiedName.toString(),
                name = it.simpleName.toString()
            )
        }

    private val cache = mutableMapOf<QualifiedName, Klass>()

    fun parse(element: Element): Klass {
        element as Symbol.ClassSymbol
        return find(
            QualifiedName(
                pkg = element.className().substringBeforeLast("."),
                name = element.className().substringAfterLast(".")
            )
        )
    }

    private fun parseInternal(element: Element): Klass {

        return when (val kotlinClassMetadata = readMetadata(element.getAnnotation(Metadata::class.java))) {
            is KotlinClassMetadata.Class -> parseClass(element, kotlinClassMetadata, processingEnv)
            else -> error("Unexpected element $kotlinClassMetadata")
        }
    }

    private fun parseClass(
        element: Element,
        metadata: KotlinClassMetadata.Class,
        processingEnvironment: ProcessingEnvironment
    ): Klass {

        val kmClass = metadata.toKmClass()

        element as Symbol.ClassSymbol

        val superclassParam = kmClass.supertypes.firstOrNull()?.let { superclass ->
            superclass.arguments.firstOrNull().let { param -> param?.type?.toType() }
        }

        val methodNameToAnnotations: Map<String, List<Annotation>> = element.members()
            .elements
            .map { it as Element }
            .filterIsInstance<Symbol.MethodSymbol>()
            .associate { it.name.toString() to listOfNotNull(it.getAnnotation(Where::class.java)) }

        val functions = kmClass.functions.asSequence()
            .filterNot { Flag.Function.IS_SYNTHESIZED.invoke(it.flags) }
            .map { func ->
                KlassFunction(
                    name = func.name,
                    parameters = func.valueParameters.map { param ->
                        FunctionParameter(
                            name = param.name,
                            type = param.type!!.toType(),
                            isTarget = false,
                            annotations = emptyList()
                        )
                    },
                    returnType = func.returnType.toType(),
                    annotationConfigs = methodNameToAnnotations[func.name] ?: emptyList(),
                    abstract = false,
                    isExtension = false
                )
            }
            .toList()

        val tableAnnotation: Table? = element.getAnnotation(Table::class.java)
        val repoAnnotation: PostgresRepository? = element.getAnnotation(PostgresRepository::class.java)

        val fieldNameToAnnotationDetails = element.members_field.elements
            .filterIsInstance<Symbol.VarSymbol>()
            .associate {
                it.name.toString() to listOfNotNull(
                    it.getAnnotation(Column::class.java),
                    it.getAnnotation(Id::class.java)
                )
            }

        return Klass(
            element = element,
            name = QualifiedName(
                pkg = processingEnvironment.elementUtils.getPackageOf(element).qualifiedName.toString(),
                name = kmClass.name.substringAfterLast("/")
            ),
            fields = kmClass.properties.map {
                Field(
                    name = it.name,
                    type = it.returnType.toType(),
                    annotations = fieldNameToAnnotationDetails[it.name] ?: emptyList()
                )
            },
            annotations = listOfNotNull(tableAnnotation, repoAnnotation),
            functions = functions,
            isInterface = element.isInterface,
            superclassParameter = superclassParam,
        )
    }

    private fun find(qn: QualifiedName): Klass {
        return cache.computeIfAbsent(qn) { simpleType ->
            when (simpleType) {
                in primitives -> Klass(name = qn)
                !in elementsByName -> error("Class $simpleType not found in sources")
                else -> parseInternal(elementsByName[simpleType]!!)
            }
        }
    }

    private fun KmType.toType(): Type {
        val classifier = classifier as KmClassifier.Class
        val packageName = classifier.name.replace("/", ".").extractPackage()
        val typeName = classifier.name.replace("/", ".").extractClassName()
        val typeArguments = arguments.mapNotNull { it.type?.toType() }
        val isNullable = Flag.Type.IS_NULLABLE.invoke(this.flags)
        return Type(
            klass = find(QualifiedName(pkg = packageName, name = typeName)),
            nullability = if (isNullable) Nullability.NULLABLE else Nullability.NON_NULLABLE,
            typeParameters = typeArguments
        )
    }
}

private fun readMetadata(metadata: Metadata): KotlinClassMetadata? = metadata.let {
    KotlinClassHeader(
        it.kind,
        it.metadataVersion,
        it.bytecodeVersion,
        it.data1,
        it.data2,
        it.extraString,
        it.packageName,
        it.extraInt
    )
}
    .let { KotlinClassMetadata.read(it) }


/**
 * com.my.company.MyClass<in kotlin.Int, out kotlin.String>
 *     group 2: com.my.company
 *     group 3: MyClass
 *     group 4: <in kotlin.Int, out kotlin.String>
 */
private val typeDeclarationPattern = "^(([\\w\\.]*)\\.)?(\\w*)(<.*>)?".toRegex()

private fun String.extractPackage() = typeDeclarationPattern.find(this)!!.groupValues[2]
private fun String.extractClassName() = typeDeclarationPattern.find(this)!!.groupValues[3]

val primitives = KotlinType.values().map { it.qn }.toSet()

enum class KotlinType(val qn: QualifiedName, val jdbcSetterName: String?) {
    BIG_DECIMAL(QualifiedName(pkg = "java.math", name = "BigDecimal"), "BigDecimal"),
    BOOLEAN(QualifiedName(pkg = "kotlin", name = "Boolean"), "Boolean"),
    BYTE_ARRAY(QualifiedName(pkg = "kotlin", name = "ByteArray"), "Bytes"),
    DATE(QualifiedName(pkg = "java.sql", name = "Date"), "Date"),
    DOUBLE(QualifiedName(pkg = "kotlin", name = "Double"), "Double"),
    FLOAT(QualifiedName(pkg = "kotlin", name = "Float"), "Float"),
    INSTANT(QualifiedName(pkg = "java.time", name = "Instant"), null),
    INT(QualifiedName(pkg = "kotlin", name = "Int"), "Int"),
    LIST(QualifiedName(pkg = "kotlin.collections", name = "List"), "Object"),
    LONG(QualifiedName(pkg = "kotlin", name = "Long"), "Long"),
    LOCAL_DATE(QualifiedName(pkg = "java.time", name = "LocalDate"), "Object"),
    LOCAL_DATE_TIME(QualifiedName(pkg = "java.time", name = "LocalDateTime"), "Object"),
    LOCAL_TIME(QualifiedName(pkg = "java.time", name = "LocalTime"), "Object"),
    MAP(QualifiedName(pkg = "kotlin.collections", name = "Map"), null),
    MUTABLE_LIST(QualifiedName(pkg = "kotlin.collections", name = "MutableList"), null),
    MUTABLE_MAP(QualifiedName(pkg = "kotlin.collections", name = "MutableMap"), null),
    STRING(QualifiedName(pkg = "kotlin", name = "String"), "String"),
    TIME(QualifiedName(pkg = "java.sql", name = "Time"), "Time"),
    TIMESTAMP(QualifiedName(pkg = "java.sql", name = "Timestamp"), "Timestamp"),
    UNIT(QualifiedName(pkg = "kotlin", name = "Unit"), null),
    UUID(QualifiedName(pkg = "java.util", name = "UUID"), "Object"),
    ZONED_DATE_TIME(QualifiedName(pkg = "java.time", name = "ZonedDateTime"), null),
    ;

    companion object {
        fun of(qualifiedName: QualifiedName): KotlinType? {
            return values().singleOrNull { it.qn == qualifiedName }
        }
    }
}

val kotlinTypeToPostgresTypeMapping = mapOf(
    KotlinType.BIG_DECIMAL to PostgresType.NUMERIC,
    KotlinType.BOOLEAN to PostgresType.BOOLEAN,
    KotlinType.BYTE_ARRAY to PostgresType.BYTEA,
    KotlinType.DATE to PostgresType.DATE,
    KotlinType.DOUBLE to PostgresType.DOUBLE,
    KotlinType.FLOAT to PostgresType.REAL,
    KotlinType.INSTANT to PostgresType.TIMESTAMP_WITH_TIMEZONE,
    KotlinType.INT to PostgresType.INTEGER,
    KotlinType.LIST to PostgresType.JSONB,
    KotlinType.LONG to PostgresType.BIGINT,
    KotlinType.LOCAL_DATE to PostgresType.DATE,
    KotlinType.LOCAL_DATE_TIME to PostgresType.TIMESTAMP,
    KotlinType.LOCAL_TIME to PostgresType.TIME,
    KotlinType.MAP to PostgresType.JSONB,
    KotlinType.MUTABLE_LIST to PostgresType.JSONB,
    KotlinType.MUTABLE_MAP to PostgresType.JSONB,
    KotlinType.STRING to PostgresType.TEXT,
    KotlinType.TIME to PostgresType.TIME,
    KotlinType.TIMESTAMP to PostgresType.TIMESTAMP_WITH_TIMEZONE,
    KotlinType.UUID to PostgresType.UUID,
)