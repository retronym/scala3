package tests.sanitization

/** <script>alert('hello')</script> */
class Script

/** < script   >alert('hello')</script
> */
class ScriptWithSpaces

/** <script>alert('hello')</script> */
class FakeSafeScript

/** Example < Second <: Third <= Fourth */
class NotATag

/** Example < Second >: Third */
class NotATagButHasGreaterThan

/** a<b */
class NotATagButNoSpaces

/**
 * test
 * ```
 * example = false
 * ```
 * <script>alert('hello')</script>
 */
class TagOutsideCode

/**
 * see [[<:<]], or [[>:>]]
 */
class LinkToTagLike
/** `x < 0` and `0 <= n < k`, then `"<cycle>"` and `<script>alert('hello')</script>`. */
class AngleBracketsInCodeSpan

/**
 * `a < b
 *
 * c > d`
 */
class CodeSpanDoesNotCrossParagraph

/** ``a ` < b`` and `<b>bold</b>` */
class DoubleBacktickCodeSpan
