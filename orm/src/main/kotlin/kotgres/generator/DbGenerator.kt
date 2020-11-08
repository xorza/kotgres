package kotgres.generator

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeVariableName
import io.github.enjoydambience.kotlinbard.`if`
import io.github.enjoydambience.kotlinbard.addClass
import io.github.enjoydambience.kotlinbard.addCode
import io.github.enjoydambience.kotlinbard.addFunction
import io.github.enjoydambience.kotlinbard.buildFile
import io.github.enjoydambience.kotlinbard.codeBlock
import io.github.enjoydambience.kotlinbard.controlFlow
import kotgres.lib.Checkable
import kotgres.lib.DbOperations
import kotgres.lib.DbOperationsImpl
import kotgres.lib.IsolationLevel
import kotgres.model.repository.Repo
import java.sql.Connection
import javax.annotation.Generated

fun generateDb(dbDescription: DbDescription): FileSpec {
    return buildFile(dbDescription.pkg, dbDescription.name + ".kt") {

        addClass(dbDescription.name) {
            addAnnotation(Generated::class)
            addSuperinterface(Checkable::class)
            primaryConstructor(
                PropertySpec.builder("ds", ClassName("javax.sql", "DataSource"), KModifier.PRIVATE).build()
            )
            addFunction("transaction") {

                val repoHolder = ClassName(dbDescription.pkg, "${dbDescription.name}RepositoryHolder")
                addParameter(ParameterSpec.builder("readOnly", Boolean::class).defaultValue("%L", false).build())
                ClassName.bestGuess(IsolationLevel::class.qualifiedName!!)
                val readCommitted = MemberName(ClassName.bestGuess(IsolationLevel::class.qualifiedName!!), "READ_COMMITTED")
                addParameter(
                    ParameterSpec.builder("isolationLevel", IsolationLevel::class).defaultValue("%M", readCommitted).build()
                )
                addParameter(
                    "block", LambdaTypeName.get(
                        receiver = repoHolder,
                        returnType = TypeVariableName("R")
                    )
                )
                addTypeVariable(TypeVariableName("R"))
                returns(TypeVariableName("R"))
                addCode {
                    controlFlow("return ds.connection.apply") {
                        addStatement("isReadOnly = readOnly")
                        addStatement("transactionIsolation = isolationLevel.javaSqlValue")
                        addStatement("autoCommit = false")
                    }
                    controlFlow(".use") {
                        controlFlow("try") {
                            addStatement("val res = %T(it).block()", repoHolder)
                            addStatement("it.commit()")
                            addStatement("res")
                        }
                        controlFlow("catch(ex: %T)", Throwable::class) {
                            addStatement("it.rollback()")
                            addStatement("throw ex")
                        }
                    }
                }
            }

            addFunction("check"){
                addModifiers(KModifier.OVERRIDE)
                returns(List::class.parameterizedBy(String::class))
                addCode{
                    controlFlow("return ds.connection.use"){
                        addStatement("listOf(")
                        indent()
                        dbDescription.repositories.forEach { repo ->
                            addStatement("%TImpl(it),", repo.superKlass.toClassName())
                        }
                        unindent()
                        addStatement(").flatMap { it.check() }")
                    }
                }
            }
        }

        addClass("${dbDescription.name}RepositoryHolder") {
            addAnnotation(Generated::class)
            primaryConstructor(
                PropertySpec.builder("connection", ClassName("java.sql", "Connection"), KModifier.PRIVATE).build()
            )
            addSuperinterface(DbOperations::class, codeBlock("%T(connection)", DbOperationsImpl::class))
            dbDescription.repositories.forEach { repo ->
                addProperty(
                    PropertySpec.builder(
                        repo.superKlass.name.name.decapitalize(),
                        ClassName(repo.superKlass.name.pkg, repo.superKlass.name.name)
                    )
                        .initializer(
                            "%T(connection)",
                            ClassName(repo.superKlass.name.pkg, repo.superKlass.name.name + "Impl")
                        )
                        .build()
                )
            }

        }
    }
}

data class DbDescription(
    val pkg: String,
    val name: String,
    val repositories: List<Repo>,
)