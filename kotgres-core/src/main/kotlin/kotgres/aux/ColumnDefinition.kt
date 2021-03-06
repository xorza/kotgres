package kotgres.aux

data class ColumnDefinition(
    val name: String,
    val nullable: Boolean,
    val type: PostgresType,
    val isId: Boolean,
){
    override fun toString() = "$name ${type}${if (!nullable) " not null" else "" }"
}