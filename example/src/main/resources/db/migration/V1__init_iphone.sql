create table "my_class"
(
    "id"              text primary key,
    "name"            text,
    "cap_city"        text                        not null,
    "longivity"       text                        not null,
    "proc"            text                        not null,
    "version"         integer                     not null,
    "date"            date                        not null,
    "bool"            boolean                     not null,
    "timestamp"       timestamp with time zone    not null,
    "uuid"            uuid                        not null,
    "time"            time without time zone      not null,
    "local_date"      date                        not null,
    "local_date_time" timestamp without time zone not null,
    "list"            jsonb                       not null,
    "enum"            text                        not null,
    "nullable_int"    integer
);