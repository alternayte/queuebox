/**
 * Prepares an error text for the `last_error` column and for a log line.
 * This is a port of `ErrorSanitizer` and `CredentialMasking` in QueueBox. The three must not
 * drift apart.
 */

/** The longest text that the sanitiser returns. */
export const MAX_ERROR_LENGTH = 2000;

const REDACTED = "[REDACTED]";
const TRUNCATION_MARKER = "...[truncated]";
const MAX_CAUSE_DEPTH = 5;
const MASK = "***";

const SECRET_KEYS = [
  "authorization", "proxy-authorization", "x-api-key", "api-key", "apikey", "cookie",
  "set-cookie", "x-auth-token", "token", "access_token", "refresh_token", "client_secret",
  "secret", "password", "pwd", "passwd", "credential", "credentials", "passphrase",
  "private_key",
];

// The schemes that carry the credential directly after the scheme name.
const AUTH_SCHEMES = ["Basic", "Bearer", "Digest", "Negotiate", "Token"];

function escape(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

// A key writes its word separator as '-', '_' or '.', so every form of one key matches one
// pattern. The pattern also accepts a key with no separator at all.
function keyAlternative(key: string): string {
  return key.split(/[-_.]/).map(escape).join("[-_.]?");
}

const QUOTED_VALUE = `"(?:\\\\.|[^"\\\\\\n])*"?|'(?:\\\\.|[^'\\\\\\n])*'?`;

// Matches "<key><separator><value>". The key accepts a prefix, so "PGPASSWORD" matches, and it
// ends on a word boundary, so "passwordless" does not.
//
// A ':' separator is the log, the YAML and the header notation, where a value can hold a space,
// so the value runs to a comma, a semicolon, a brace, a bracket, an ampersand, a quote or the
// end of the line. An '=' separator is the environment, the query and the connection string
// notation, where the value ends at the first whitespace. Both stop at a semicolon, which is
// what bounds a connection string value.
const SECRET_PATTERN = new RegExp(
  `([A-Za-z0-9_]*(?:${SECRET_KEYS.map(keyAlternative).join("|")}))\\b"?\\s*(?:` +
    `:+\\s*(?:${QUOTED_VALUE}|(?:(?:${AUTH_SCHEMES.join("|")})\\s+)?[^,;}\\]&\\n"]*)` +
    "|" +
    `=+\\s*(?:${QUOTED_VALUE}|(?:(?:${AUTH_SCHEMES.join("|")})\\s+)?[^\\s,;}\\]&\\n"]*)` +
    ")",
  "gi",
);

// Matches a bare authentication scheme and the token that follows it.
//
// The token must look like a credential, or an ordinary sentence loses a word. "Token" is both a
// scheme name and an English word, so "the token bucket is empty" must stay whole. A credential
// is long, or it carries a digit or an '='. The sixteen character line keeps
// "Digest authentication failed" readable.
const SCHEME_PATTERN = new RegExp(
  `\\b(${AUTH_SCHEMES.join("|")})\\s+` +
    "(?=[A-Za-z0-9._~+/\\-]*[0-9=]|[A-Za-z0-9._~+/\\-]{16})" +
    "[A-Za-z0-9._~+/\\-]+=*",
  "gi",
);

// The user information of a URL. The host and the port stay, because an operator needs them.
const SCHEME_PART = "([a-zA-Z][a-zA-Z0-9+.-]*:/{1,2})";
const USER_PART = "[^\\s/?#@]*:";
const HOST_AFTER = "(?=[^/?#\\s@]*(?:[/?#\\s]|$))";
const PLAUSIBLE_HOST_AFTER = "(?=[^/?#\\s@]*:[0-9]+(?:[/?#\\s]|$)|[^/?#\\s@]*[/?#])";

// The runs are bounded. An unbounded run swallowed the host of an ordinary sentence that named
// a URL and an address. A comma followed by whitespace is prose, never a user information.
const SLASH_RUN = "(?:(?!://)(?!,\\s)[^?#]){0,200}";
const NO_SLASH_RUN = "(?:(?!,\\s)[^/?#]){0,200}";

const USER_INFO_SPACE_WITH_SLASH = new RegExp(
  SCHEME_PART + USER_PART + SLASH_RUN + "\\s" + SLASH_RUN + "@" + PLAUSIBLE_HOST_AFTER, "g");
const USER_INFO_SPACE_NO_SLASH = new RegExp(
  SCHEME_PART + USER_PART + NO_SLASH_RUN + "\\s" + NO_SLASH_RUN + "@" + HOST_AFTER, "g");
const USER_INFO_NO_SPACE = new RegExp(
  SCHEME_PART + "(?:[^\\s/@]*:(?:(?!://)\\S)*|[^\\s/@]*)@" + HOST_AFTER, "g");
const PASSWORD_PARAMETER = /([?&;](?:password|pwd|secret|token))=[^&;\s]*/gi;

function maskUrl(url: string): string {
  return url
    .replace(USER_INFO_SPACE_WITH_SLASH, `$1${MASK}@`)
    .replace(USER_INFO_SPACE_NO_SLASH, `$1${MASK}@`)
    .replace(USER_INFO_NO_SPACE, `$1${MASK}@`)
    .replace(PASSWORD_PARAMETER, `$1=${MASK}`);
}

/**
 * Redact every secret value in the text, then truncate the result.
 *
 * @param text the text, which can be undefined
 * @returns the safe text, or undefined when the input was undefined
 */
export function sanitize(text: string | undefined): string | undefined {
  if (text === undefined) {
    return undefined;
  }

  let redacted = text.replace(SECRET_PATTERN, (_match, key: string) => `${key}=${REDACTED}`);

  // A URL password is not a "key=value" pair.
  redacted = maskUrl(redacted);

  redacted = redacted.replace(SCHEME_PATTERN, (_match, scheme: string) => `${scheme} ${REDACTED}`);

  return redacted.length <= MAX_ERROR_LENGTH
    ? redacted
    : redacted.slice(0, MAX_ERROR_LENGTH - TRUNCATION_MARKER.length) + TRUNCATION_MARKER;
}

/**
 * Name the failure and every cause below it, then redact the whole text.
 *
 * A driver puts the connection string in the message of the cause, not of the wrapper. The chain
 * therefore reaches the redaction, and no cause message escapes it.
 *
 * @param failure the thrown value, which is not always an Error
 * @returns the safe text
 */
export function sanitizeError(failure: unknown): string | undefined {
  const parts: string[] = [];
  let current: unknown = failure;
  let depth = 0;

  while (current !== undefined && current !== null && depth < MAX_CAUSE_DEPTH) {
    if (current instanceof Error) {
      parts.push(`${current.name}: ${current.message}`);
      const next: unknown = current.cause;
      current = next === current ? undefined : next;
    } else {
      parts.push(String(current));
      current = undefined;
    }

    depth += 1;
  }

  return sanitize(parts.join(" | caused by "));
}
