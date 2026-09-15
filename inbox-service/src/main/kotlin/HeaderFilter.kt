package org.nxtspec

/**
 * Evaluates the header filter of one inbox source.
 *
 * A message passes when it matches every `require` rule and no `exclude` rule. Header names match
 * case-insensitively, and values match exactly.
 */
class HeaderFilter(config: HeaderFilterConfig) {
    private val require = config.require.map(::CompiledRule)
    private val exclude = config.exclude.map(::CompiledRule)

    /**
     * Returns null when the message passes, or a description of the first rule that stops it. The
     * description names the rule, never a header value.
     */
    fun firstFailure(headers: Map<String, String>): String? {
        val byName = headers.entries.associate { it.key.lowercase() to it.value }
        require.forEachIndexed { index, rule ->
            if (!rule.matches(byName)) return "require[$index] (${rule.describe()})"
        }
        exclude.forEachIndexed { index, rule ->
            if (rule.matches(byName)) return "exclude[$index] (${rule.describe()})"
        }
        return null
    }

    private class CompiledRule(private val rule: HeaderRule) {
        private val name = rule.header.lowercase()
        private val pattern = rule.matches?.let(::compileTopicPattern)
        private val values = rule.`in`?.toSet()

        fun matches(byName: Map<String, String>): Boolean {
            val value = byName[name] ?: return false
            return when {
                rule.equals != null -> value == rule.equals
                values != null -> value in values
                pattern != null -> pattern.matches(value)
                else -> true
            }
        }

        fun describe(): String = when {
            rule.equals != null -> "${rule.header} equals"
            values != null -> "${rule.header} in"
            pattern != null -> "${rule.header} matches ${rule.matches}"
            else -> "${rule.header} exists"
        }
    }
}
