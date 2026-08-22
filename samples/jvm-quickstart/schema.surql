DEFINE TABLE OVERWRITE person SCHEMAFULL;

DEFINE FIELD OVERWRITE name ON person TYPE option<string>;
DEFINE FIELD OVERWRITE age ON person TYPE option<int>;
DEFINE FIELD OVERWRITE display_name ON person TYPE option<string>;
DEFINE FIELD OVERWRITE address ON person TYPE option<object>;
DEFINE FIELD OVERWRITE address.city ON person TYPE option<string>;
