package org.nxtspec

/** Validates the header filter rules of an inbox source. */
internal object HeaderFilterValidator {
    private fun setVia(yamlPath: String) =
        "Set via '$yamlPath' in YAML or ${EnvConfigLoader.yamlPathToEnvKey(yamlPath)} env var."

    fun validateRule(rule: HeaderRule, yamlPath: String) {
        require(rule.header.isNotBlank()) {
            "Header filter rule '$yamlPath' needs a header name. " + setVia("$yamlPath.header")
        }
        val tests = listOfNotNull(
            rule.equals?.let { "equals" },
            rule.`in`?.let { "in" },
            rule.matches?.let { "matches" },
            rule.exists?.let { "exists" }
        )
        require(tests.size == 1) {
            "Header filter rule '$yamlPath' must set exactly one of equals, in, matches or exists, " +
                "but it sets ${if (tests.isEmpty()) "none" else tests.joinToString(", ")}. " +
                setVia("$yamlPath.equals")
        }
        require(rule.exists != false) {
            "Header filter rule '$yamlPath' sets exists to false. Put an 'exists: true' rule under " +
                "'exclude' instead. " + setVia("$yamlPath.exists")
        }
        require(rule.`in` == null || rule.`in`.isNotEmpty()) {
            "Header filter rule '$yamlPath' has an empty 'in' list. " + setVia("$yamlPath.in")
        }
        require(rule.matches == null || rule.matches.isNotBlank()) {
            "Header filter rule '$yamlPath' has a blank 'matches' pattern. " + setVia("$yamlPath.matches")
        }
    }
}
