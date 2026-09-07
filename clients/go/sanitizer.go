package queuebox

import (
	"errors"
	"regexp"
	"strings"

	"github.com/dlclark/regexp2"
)

// MaxErrorLength is the longest text that Sanitize returns.
const MaxErrorLength = 2000

const (
	redacted         = "[REDACTED]"
	truncationMarker = "...[truncated]"
	maxCauseDepth    = 5
	credentialMask   = "***"
	// The redaction window is the output length plus room for the longest plausible secret.
	redactionMargin   = 512
	noMatchLimit      = -1
	replaceEveryMatch = -1
)

// This file is a port of ErrorSanitizer and CredentialMasking in QueueBox. The four
// implementations must not drift apart, so `clients/redaction-corpus.json` states the behaviour
// and every library runs it.
//
// The port uses regexp2 rather than the standard library. The patterns depend on lookahead, and
// Go's own regexp is RE2, which has none. Copying the patterns character for character is the
// point: a redaction heuristic that is re-derived, and that therefore differs in a corner, is a
// security defect rather than a difference of idiom.

var secretKeys = []string{
	"authorization", "proxy-authorization", "x-api-key", "api-key", "apikey", "cookie",
	"set-cookie", "x-auth-token", "token", "access_token", "refresh_token", "client_secret",
	"secret", "password", "pwd", "passwd", "credential", "credentials", "passphrase",
	"private_key",
}

// The schemes that carry the credential directly after the scheme name.
var authSchemes = []string{"Basic", "Bearer", "Digest", "Negotiate", "Token"}

const quotedValue = `"(?:\\.|[^"\\\n])*"?|'(?:\\.|[^'\\\n])*'?`

// A key writes its word separator as '-', '_' or '.', so every form of one key matches one
// pattern. The pattern also accepts a key with no separator at all.
func keyAlternative(key string) string {
	parts := strings.FieldsFunc(key, func(r rune) bool { return r == '-' || r == '_' || r == '.' })

	for i, part := range parts {
		parts[i] = regexp.QuoteMeta(part)
	}

	return strings.Join(parts, "[-_.]?")
}

var (
	// These two need no lookahead, so they use the standard library, which is RE2 and runs in
	// linear time. regexp2 backtracks and costs about forty times more per character.
	secretPattern *regexp.Regexp
	schemePattern *regexp.Regexp

	// Only the URL shapes need lookahead, so only they pay for regexp2.
	userInfoSpaceWithSlash *regexp2.Regexp
	userInfoSpaceNoSlash   *regexp2.Regexp
	userInfoNoSpace        *regexp2.Regexp
	passwordParameter      *regexp.Regexp
)

func init() {
	alternatives := make([]string, 0, len(secretKeys))
	for _, key := range secretKeys {
		alternatives = append(alternatives, keyAlternative(key))
	}

	schemePrefix := `(?:(?:` + strings.Join(authSchemes, "|") + `)\s+)?`

	// Matches "<key><separator><value>". The key accepts a prefix, so "PGPASSWORD" matches, and
	// it ends on a word boundary, so "passwordless" does not.
	//
	// A ':' separator is the log, the YAML and the header notation, where a value can hold a
	// space, so the value runs to a comma, a semicolon, a brace, a bracket, an ampersand, a
	// quote or the end of the line. An '=' separator is the environment, the query and the
	// connection string notation, where the value ends at the first whitespace. Both stop at a
	// semicolon, which is what bounds a connection string value.
	// The key prefix and the scheme name are BOUNDED. An unbounded `*` in front of a required
	// literal makes the whole pattern quadratic in the length of the text, and the redaction runs
	// on every failure, so a long driver message stalled the worker. See the benchmark.
	secretPattern = regexp.MustCompile(
		`(?i)([A-Za-z0-9_]{0,64}(?:` + strings.Join(alternatives, "|") + `))\b"?\s*(?:` +
			`:+\s*(?:` + quotedValue + `|` + schemePrefix + `[^,;}\]&\n"]*)` +
			`|` +
			`=+\s*(?:` + quotedValue + `|` + schemePrefix + `[^\s,;}\]&\n"]*)` +
			`)`)

	// Matches a bare authentication scheme and the token that follows it.
	//
	// The token must look like a credential, or an ordinary sentence loses a word. "Token" is
	// both a scheme name and an English word, so "the token bucket is empty" must stay whole. A
	// credential is long, or it carries a digit or an '='. The sixteen character line keeps
	// "Digest authentication failed" readable.
	// The original pattern decides with a lookahead whether the token LOOKS like a credential.
	// RE2 has no lookahead, so the pattern captures the token and looksLikeACredential applies
	// the same rule. The shared corpus proves the two agree.
	schemePattern = regexp.MustCompile(
		`(?i)\b(` + strings.Join(authSchemes, "|") + `)(\s+)([A-Za-z0-9._~+/\-]+=*)`)

	const (
		scheme             = `([a-zA-Z][a-zA-Z0-9+.-]{0,31}:/{1,2})`
		user               = `[^\s/?#@]*:`
		hostAfter          = `(?=[^/?#\s@]*(?:[/?#\s]|$))`
		plausibleHostAfter = `(?=[^/?#\s@]*:[0-9]+(?:[/?#\s]|$)|[^/?#\s@]*[/?#])`
		// The runs are bounded. An unbounded run swallowed the host of an ordinary sentence
		// that named a URL and an address. A comma followed by whitespace is prose, never a
		// user information.
		slashRun   = `(?:(?!://)(?!,\s)[^?#]){0,200}`
		noSlashRun = `(?:(?!,\s)[^/?#]){0,200}`
	)

	userInfoSpaceWithSlash = regexp2.MustCompile(scheme+user+slashRun+`\s`+slashRun+`@`+plausibleHostAfter, regexp2.None)
	userInfoSpaceNoSlash = regexp2.MustCompile(scheme+user+noSlashRun+`\s`+noSlashRun+`@`+hostAfter, regexp2.None)
	userInfoNoSpace = regexp2.MustCompile(scheme+`(?:[^\s/@]*:(?:(?!://)\S)*|[^\s/@]*)@`+hostAfter, regexp2.None)
	passwordParameter = regexp.MustCompile(`(?i)([?&;](?:password|pwd|secret|token))=[^&;\s]*`)
}

// looksLikeACredential decides whether the token after a bare scheme name is a credential.
//
// "Token" is both a scheme name and an English word, so "the token bucket is empty" must stay
// whole. A credential is long, or it carries a digit or an '='. The sixteen character line keeps
// "Digest authentication failed" readable.
func looksLikeACredential(token string) bool {
	if len(token) >= 16 {
		return true
	}

	return strings.ContainsAny(token, "0123456789=")
}

func replaceAll(pattern *regexp2.Regexp, input, replacement string) string {
	// A replacement cannot fail on a pattern that already compiled, so a failure leaves the text
	// alone rather than printing a half redacted one.
	result, err := pattern.Replace(input, replacement, noMatchLimit, replaceEveryMatch)
	if err != nil {
		return input
	}

	return result
}

// maskURL replaces the user information and the password parameter of every URL.
// The host and the port stay, because an operator needs them.
func maskURL(text string) string {
	// The three shapes below are the only patterns that backtrack. A text with no scheme cannot
	// match any of them, and most error texts carry no URL at all, so the guard keeps the common
	// failure cheap.
	if !strings.Contains(text, ":/") {
		return text
	}

	masked := replaceAll(userInfoSpaceWithSlash, text, "${1}"+credentialMask+"@")
	masked = replaceAll(userInfoSpaceNoSlash, masked, "${1}"+credentialMask+"@")
	masked = replaceAll(userInfoNoSpace, masked, "${1}"+credentialMask+"@")

	return masked
}

// Sanitize redacts every secret value in the text and truncates the result.
// It prepares an error text for the last_error column and for a log line.
func Sanitize(text string) string {
	// The redaction runs before the truncation, so a secret that straddles the cut cannot print
	// a fragment. It runs over a BOUNDED window, because everything past the window is dropped
	// and never reaches the output. The margin holds the longest plausible secret, so a value
	// that starts inside the kept text is still matched whole.
	if len(text) > MaxErrorLength+redactionMargin {
		text = text[:MaxErrorLength+redactionMargin]
	}

	result := secretPattern.ReplaceAllString(text, "${1}="+redacted)

	// A URL password is not a "key=value" pair.
	result = maskURL(result)

	result = schemePattern.ReplaceAllStringFunc(result, func(match string) string {
		groups := schemePattern.FindStringSubmatch(match)
		if groups == nil || !looksLikeACredential(groups[3]) {
			return match
		}

		return groups[1] + " " + redacted
	})

	result = passwordParameter.ReplaceAllString(result, "${1}="+credentialMask)

	if len(result) <= MaxErrorLength {
		return result
	}

	return result[:MaxErrorLength-len(truncationMarker)] + truncationMarker
}

// SanitizeError names the failure and every cause below it, then redacts the whole text.
//
// A driver puts the connection string in the message of the cause, not of the wrapper. The chain
// therefore reaches the redaction, and no cause message escapes it.
func SanitizeError(err error) string {
	if err == nil {
		return ""
	}

	parts := make([]string, 0, maxCauseDepth)
	current := err

	for depth := 0; current != nil && depth < maxCauseDepth; depth++ {
		parts = append(parts, current.Error())

		next := errors.Unwrap(current)
		if next == current {
			break
		}

		current = next
	}

	// Every wrapper of a Go error already carries the text of its cause, so the first entry is
	// usually the whole chain. The loop still walks it, because a wrapper is free not to.
	return Sanitize(strings.Join(parts, " | caused by "))
}
