# Kotgres[ql]

Not an ORM.

Like ORM maps Kotlin classes to DB tables, only better.

`Kotgres = ORM - bullshit`

## Quick start

#### Gradle
```kotlin
plugins {
    kotlin("kapt")
    kotlin("plugin.serialization") // for serializing collections as JSON
}

repositories {
    jcenter()
}

dependencies {
  implementation("com.github.mfarsikov:kotgres-core:0.1.0") // library containing annotations and classes used in compile time

  kapt("com.github.mfarsikov:kotgres-kapt:0.1.0") // Kotlin annotation processor, generates repositories code before compilation
}

kapt {
  arguments {
    arg("kotgres.db.qualifiedName", "my.pack.DB") // default database class name
    arg("kotgres.spring", "false") // marks database class as Spring's component
  }
}
```

#### Create entities and declare repositories
```kotlin
import kotgres.annotations.Id
import kotgres.annotations.PostgresRepository
import kotgres.aux.Repository

data class Person(
  @Id
  val id: UUID,
  val name: String,
  val birthDate: LocalDate,
)

@PostgresRepository
interface PersonRepository : Repository<Person> {
    fun findBy(birthDate: LocalDate): List<Person>
}

```
#### Generate the code
`./gradlew kaptKotlin` generates in the folder `build/generated/source/kapt` two classes:
`PersonRepositoryImpl` and `DB`

#### Usage
```kotlin
val db = DB(dataSource) // create DB access object

db.transaction {
  // inside the transaction all repositories are accessible through 'this'
  personRepository.save(
    Person(
      id = UUID.random(),
      name = "John Doe",
      birthDate = LocalDate.now(),
    )
  )
}

val bornToday = db.transaction(readOnly = true) {
    personRepository.findBy(birthDate = LocalDate.now())
}

```
## Synopsis

* Maps Kotlin classes to Postgres tables
* Generates SpringData-like repositories with
    * predefined query methods(`saveAll`, `deleteAll`, `findAll`)
    * custom query methods (like `findByLastName`)
    * methods using native SQL (`@Query("select ...")`)
    * query methods returning projections
* Code and queries are generated at compile time
* Generated code is properly formatted and human-friendly
* Explicit transaction management (DSL, not annotations driven)
* Postgres specific
* Uses native SQL and JDBC
* Uses immutable Kotlin data classes as 'entities'
* Maps nested object's properties into a single table (like JPA `@Embeddable`)
* Serializes Kotlin collections as JSONB type in postgres
* Generates schema validations

Unlike popular ORM:

* No reflection and runtime magic
* No lazy loading
* No automatic joins, and sub selects (no "N+1" problem)
* No query languages other than SQL
* No vendor agnostic
* No implicit type conversions
* No queries triggered by entity's getters
* No "managed" state
* No caches
* No "object relational impedance mismatch"
* No inheritance resolve strategies
* No transaction managers

## Rationale
The intention was to make database interactions (queries and transactions) explicit.
Generate boiler plate code (like trivial queries, and result mappings). 
Give ability to write complex queries, and map their results automatically.
Use full power of Postrgesql (such as JSON queries and full text search queries).

Avoid accidental complexity

## Entities
### Name conversions
Entity is a Kotlin data class. 
It should be declared in the source code, not imported from a library (maybe this will be changed in future).
Entity should have property types listed in [type mappings](#Type mappings).

Table and column names are the class and property names converted to `lower_snake_case` 

Table name can be changed using annotation `@Table(name = "my_table")` on class level.
Column name can be changed using `@Column(name = "my_column")` on property level

See [type mappings](#Type mappings)
## Repositories

Each repository interface must be annotated with `@PostgresRepository` and extend `Repository`

### Predefined methods
`saveAll`, `save`, `findAll`, `deleteAll`
If entity has an identity (property marked as `@Id`) there is generated: `ON CONFLICT DO UPDATE`

### Query methods
#### Method name
In cases of querying method a method name does not matter and could be anything.
For both cases will be generated the same code:
```kotlin
fun findByFirstName(firstName: String): List<Person>
fun select(firstName: String): List<Person>
```
The generated query will be something like: `SELECT first_name, last_name FROM person WHERE first_name = ?`. 
The `first_name = ?` is 'inferred' based on the parameter name, so Parameter names should match entity's property names.

#### Return type
Return type could be one of:
* Entity (throws an exception if there is no result or if there is more than one result)
* Nullable entity (throws if there is more than one result)
* List of entities

```kotlin
fun select(firstName: String): List<Person>
fun findById(id: UUID): Person?
fun findByLicenseNumber(licenseNumber: String): Person
```
#### Projections
Besides entities query methods can return projections. For example for entity
```kotlin
data class Person(val firstName: String, val lastName: String, val age: Int)
```
projection could be any data class having some of Entity's fields:
```kotlin
data class PersonProjection1(val firstName: String, val lastName: String)
data class PersonProjection2(val age: Int)
```
and generated code will query only those required fields
```kotlin
fun findByFirstName(firstName: String): List<PersonProjection1>
fun select(id: UUID): PersonProjection2?
```
#### @Where and complex conditions
If method has more than one parameter, they are combined using `AND` logic.
Parameters compared using equality checks only.
In case if more sophisticated logic is required `@Where` annotation should be used:
```kotlin
@Where("first_name like :namePattern OR birth_date <= :birthDate")
fun select(namePattern: String, birthDate: LocalDate): List<Person>
```

#### Cusom @Query methods
User can define any custom query, which is mapped to any data class. In this case column names in result set should match 
projection class field names (up to camelCase to snake_case conversion) 
```kotlin
@Query("""
    SELECT p.first_name, p.last_name, d.age
    FROM person AS p
    JOIN documents AS d ON p.id = d.person_id
    WHERE p.first_name like :namePattern
""")
fun select(namePattern: String): PersonProjection 
```

### Delete methods
Same as find methods, except: it returns nothing, and it's name should start from a `delete` word.

## Database object
Database object gives access to transactions DSL and contains all the generated repositories.

```kotlin
val db = DB(dataSource)

val johns = db.transaction {
    // the only way to obtain a repository is to start a transaction
    personRepository.selectAllWhere(lastName = "John")
}
```

### Spring support
DB objects could be marked as Spring components:

`build.gradle.kt`
```kotlin
kapt {
  arguments {
    arg("kotgres.spring", "false")
  }
}
```
generated class:
```kotlin
import org.springframework.stereotype.Component

@Generated
@Component
public class DB(
  private val ds: DataSource
) {
...
```
So it could be instantiated and further injected by Spring.

## Transactions
Transaction can be savepointed, rolled back (either completely or to certain savepoint).
Each successful transaction completion automatically commits.
Any exception - rolls back.
For performance optimisation read only transactions could be marked as `readonly = true`:
```kotlin
val johns = db.transaction(readonly = true) {
  personRepository.selectAllWhere(lastName = "John")
}
```
Default isolation level (READ_COMMITTED) can be replaced for each transaction:
```kotlin
val johns = db.transaction(isolationLevel = IsolationLevel.SERIALIZABLE) {
    ...
}
```

## Database verification
```kotlin
DB(dataSource).check()
```
Checks all underlying repositories and returns list of errors or empty list if everything is ok.

Checks for absent/extra fields, type/nullability mismatch, key fields.

## Type mappings

|Kotlin type              | Postgresql type             |
|-------------------------|-----------------------------|
|java.math.BigDecimal     | numeric                     |
|kotlin.Boolean           | boolean                     |
|kotlin.ByteArray         | bytea                       |
|java.sql.Date            | date                        |
|kotlin.Double            | double precision            |
|kotlin.Float             | real                        |
|kotlin.Int               | integer                     |
|kotlin.collections.List  | jsonb                       |
|kotlin.Long              | bigint                      |
|java.time.LocalDate      | date                        |
|java.time.LocalDateTime  | timestamp without time zone |
|java.time.LocalTime      | time without time zone      |
|kotlin.collections.Map   | jsonb                       |
|kotlin.String            | text                        |
|java.sql.Time            | time without time zone      |
|java.sql.Timestamp       | timestamp with time zone    |
|java.util.UUID           | uuid                        |

## Example
See `example` project

`./gradlew example:kaptKotlin` generates database classes in `example/build/generated/source/kapt/main`

`./gradlew example:test` runs real queries against DB in docker container (requires Docker)

`./gradlew example:run` runs Main application in `example` project, requires running Postgres.
