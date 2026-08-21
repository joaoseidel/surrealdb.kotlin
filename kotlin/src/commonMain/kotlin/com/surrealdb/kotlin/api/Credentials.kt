package com.surrealdb.kotlin.api

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What a session authenticates as.
 *
 * The `signin` RPC takes one flat object and reads the *set* of keys in it as
 * the level to authenticate at: `{user, pass}` is root, adding `ns` makes it a
 * namespace-level user, adding `db` as well makes it a database-level one, and
 * `{ns, db, ac, …}` is a record access method. Sending a key too many or too
 * few does not report a key, it authenticates at a different level, where the
 * user does not exist, and answers `There was a problem with authentication`.
 * A namespace-level user who also sends `db` gets that message. So does a root
 * user who sends `ns`. So does a caller who spells `ns` as `namespace`, because
 * an unrecognised key is ignored rather than refused. It is the same message a
 * wrong password gets, which is how a working password comes to be doubted.
 *
 * Each case here sends exactly the keys its level takes, so the level is chosen
 * by the type rather than assembled by hand.
 *
 * @see Session.signin
 * @see Session.signup
 */
public sealed interface Credentials {
    /** The shapes the `signin` RPC accepts. A [Token] is not one; [Session.authenticate] takes that. */
    public sealed interface ForSignIn : Credentials

    /**
     * The shapes the `signup` RPC accepts, which is a record access method and
     * nothing else. Root, namespace and database users all answer
     * `There was a problem with signing up`, as does a record sign-up that
     * omits `ac`.
     */
    public sealed interface ForSignUp : ForSignIn

    /** A root user, defined by `DEFINE USER … ON ROOT`. */
    public data class RootUser(
        public val user: String,
        public val pass: String,
    ) : ForSignIn

    /** A user defined by `DEFINE USER … ON NAMESPACE` in [namespace]. */
    public data class NamespaceUser(
        public val namespace: Namespace,
        public val user: String,
        public val pass: String,
    ) : ForSignIn

    /** A user defined by `DEFINE USER … ON DATABASE` in [namespace] and [database]. */
    public data class DatabaseUser(
        public val namespace: Namespace,
        public val database: Database,
        public val user: String,
        public val pass: String,
    ) : ForSignIn

    /**
     * A record signed in or up through the access method named by [access],
     * defined by `DEFINE ACCESS … TYPE RECORD`.
     *
     * [vars] are the variables the access method's own `SIGNIN` and `SIGNUP`
     * queries read as `$email`, `$pass` and whatever else they name. They are
     * sent flattened beside `ac` rather than under a `vars` key: a `vars` key
     * is not read, so the queries see nothing and the server answers
     * `No record was returned`, which is what a wrong password answers.
     */
    public data class RecordUser(
        public val namespace: Namespace,
        public val database: Database,
        public val access: String,
        public val vars: JsonObject,
    ) : ForSignUp {
        init {
            val reserved = vars.keys.filter { it in RESERVED_VARIABLES }
            require(reserved.isEmpty()) {
                "A record access variable cannot be named ${reserved.sorted().joinToString()}: " +
                    "the server binds $RESERVED_VARIABLES itself, so flattening one beside them " +
                    "would sign in somewhere else and the access query would still not see it."
            }
        }
    }

    /**
     * The parameters of a `signin` or `signup` call, sent exactly as given.
     *
     * This is the escape for an access method this version does not model. It
     * is checked by nothing, including the reserved names [RecordUser] refuses.
     */
    public data class Raw(
        public val params: JsonObject,
    ) : ForSignUp

    /** A JWT, presented to the `authenticate` RPC rather than to `signin`. */
    public data class Token(
        public val token: String,
    ) : Credentials
}

private val RESERVED_VARIABLES = setOf("ns", "db", "ac")

internal fun Credentials.ForSignIn.toParams(): JsonObject =
    when (this) {
        is Credentials.RootUser -> {
            buildJsonObject {
                put("user", user)
                put("pass", pass)
            }
        }

        is Credentials.NamespaceUser -> {
            buildJsonObject {
                put("user", user)
                put("pass", pass)
                put("ns", namespace.value)
            }
        }

        is Credentials.DatabaseUser -> {
            buildJsonObject {
                put("user", user)
                put("pass", pass)
                put("ns", namespace.value)
                put("db", database.value)
            }
        }

        is Credentials.RecordUser -> {
            buildJsonObject {
                put("ns", namespace.value)
                put("db", database.value)
                put("ac", access)
                vars.forEach { (name, value) -> put(name, value) }
            }
        }

        is Credentials.Raw -> {
            params
        }
    }
