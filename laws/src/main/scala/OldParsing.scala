package laws

import annotation.tailrec
import collection.mutable.Builder

object Parsing {
  /** `Line` encapsulates numbered lines from a text file,
    * either bare (everything in `left`) or parsed into the form `left`-`sep`-`rights`, where
    * - `left` is spaces and alphabetic only, plus `!` and `$`
    * - `sep` is a nonempty separator that does not start with whitespace or an alphabetic character
    * - `rights` is a list of things that perhaps can be split into subthings (your job to sub-parse)
    *
    * The line number is in `index`; if the expression covers multiple lines because of line-continuation, `index` will be the first line
    */
  case class Line(left: String, sep: String, rights: Vector[String], index: Int) {
    lazy val whole = if (isSplit) left + sep + right else left
    lazy val right = if (isSplit) rights.mkString("\n") else ""
    lazy val indent = " "*left.length
    def isSplit = sep.nonEmpty
    def isWhite = !isSplit && Line.AllWhite.pattern.matcher(left).matches
    
    /** Try to merge this with a compatible following line (same separator, following line has whitespace instead of left text; parsed lines only) */
    def merge(l: Line): Option[Line] =
      if (!(isSplit && l.isSplit && sep == l.sep && indent == Line.detab(l.left))) None
      else Some(new Line(left, sep, rights ++ l.rights, index))
    
    /** This line after removing trailing double slash plus whatever follows it (whole lines only) */
    def decomment = 
      if (isSplit) this
      else new Line({ val i = left indexOf "//"; if (i<0) left else left take i }, sep, rights, index)
      
    /** This line without whitespace; if split, left and rights will be trimmed; otherwise only right whitespace is removed if the line is bare */
    def trimmed =
      if (isSplit) new Line(left.trim, sep, rights.map(_.trim), index)
      else new Line(left.reverse.dropWhile(_.isWhitespace).reverse, sep, rights, index)
      
    /** This line parsed with a default delimiter if it's not yet parsed; otherwise just identity */
    def parsed = if (isSplit) this else Line.apply(left, index)

    /** This line parsed with a default delimiter, allowing any key, if it's not yet parsed; otherwise just identity */
    def anykeyParsed = if (isSplit) this else Line.anykey(left, index)
  }
  object Line {
    /** Pattern that parsed lines must obey */
    def mkSepRegex(sep: String) = ("""^((?:\s|\w|!|\$|\?)+)(""" + java.util.regex.Pattern.quote(sep) + """)(.*)""").r
    def anyKeyRegex(sep: String) = ("""^(?:\s*)(\S+)(?:\s+)(""" + java.util.regex.Pattern.quote(sep) + """)(?:\s*)(.*)""").r
    /** Tap separator */
    val JustTab = """\t""".r
    /** Whitespace separator */
    val AllWhite = """^\s*$""".r
    /** Long right arrow separator */
    val ArrowRegex = mkSepRegex("-->")
    val AnykeyArrowRegex = anyKeyRegex("-->")
    /** Triple dots separator */
    val DotsRegex = mkSepRegex("...")
    val AnykeyDotsRegex = anyKeyRegex("...")
    
    val emptyRight = Vector("")
    val empty = new Line("", "", emptyRight, -1)
    
    /** Converts tabs to eight spaces */
    def detab(s: String) = JustTab.replaceAllIn(s,"        ")
    
    /** Given a string and a line number, wrap it as an unparsed `Line` */
    def bare(s: String, i: Int) = new Line(s, "", emptyRight, i)
    
    /** Given a string and a line number, try to parse it with either arrow or dots separators; if that doesn't work, keep it unparsed */
    def apply(s: String, i: Int): Line = s match {
      case ArrowRegex(l, s, r) => new Line(l, s,  Vector(r),  i)
      case DotsRegex(l, s, r)  => new Line(l, s,  Vector(r),  i)
      case x                   => new Line(x, "", emptyRight, i)
    }
    
    /** Given a string and a line number, try to parse it with either arrow or dots separators 
      * but allow any non-whitespace key.  Also, separate the right-hand side by whitespace.
      */
    def anykey(s: String, i: Int): Line = s match {
      case AnykeyArrowRegex(l,s,r) => new Line(l, s, Vector(r.split("\\s+"): _*).filter(_.nonEmpty), i)
      case AnykeyDotsRegex(l,s,r)  => new Line(l, s, Vector(r.split("\\s+"): _*).filter(_.nonEmpty), i)
      case x                       => new Line(x, "", emptyRight, i)
    }
    
    /** Given a string and line number, try to parse it with custom separators; if that doesn't work keep it unparsed */
    def custom(s: String, i: Int, firstSep: String, moreSeps: String*): Line = {
      for (sep <- (firstSep +: moreSeps)) {
        val CustomRegex = mkSepRegex(sep)
        s match {
          case CustomRegex(l, s, r) => return new Line(l, s, Vector(r), i)
          case _ =>
        }
      }
      Line.bare(s, i)
    }
    
    /** Given a string and line number, first try to parse it with custom separators, then fall back to arrow/dots parse */
    def apply(s: String, i: Int, firstSep: String, moreSeps: String*): Line = {
      val x = custom(s, i, firstSep, moreSeps: _*)
      if (!x.isSplit) apply(s,i) else x
    }
  }
  
  /** `Lines` encapsulates operations on blocks of lines (comments, merging, etc.).
    * Note that the methods appear in the source in approximately the order you would normally use them
    * (tokenize, then decomment, then parsed, etc.)
    */
  case class Lines(lines: Vector[Line]) {
    @inline def underlying = lines
    @inline def isEmpty = lines.isEmpty
    
    /** Get new `Lines` by mapping the underlying block of lines */
    def ap(f: Vector[Line] => Vector[Line]) = new Lines(f(lines))
    /** Get new `Lines` by mapping each underlying `Line` */
    def map(f: Line => Line) = ap(_.map(f))
    
    /** Chops lines up into groups whenever they are `blanks` empty (whitespace-only) lines in a row.
      * Only works on unparsed lines; may not give what you expect if you have trimmed or decommented
      */
    def tokenize(blanks: Int): Vector[Lines] = if (blanks < 0) Vector(this) else {
      lines.scanLeft( (0, 0, Line.empty) ){ (acc, line) =>
        val (blank, group, _) = acc
        if (line.isWhite) (blank + 1, if (blank+1 == blanks) group+1 else group, line)
        else (0, group, line)
      }.drop(1).
        groupBy(_._2).          // Group by group number
        toVector.sortBy(_._1).  // Keep original order
        map(_._2.map(_._3)).    // Only keep lines
        map(v => v.dropWhile(_.isWhite).reverse.dropWhile(_.isWhite).reverse).   // Trim white lines from both ends
        map(v => new Lines(v))  // Pack into Lines class
    }
    
    /** Remove comments (unsplit lines only) */
    def decomment = map(_.decomment)
    
    /** Finds separators for any lines that haven't already been parsed */
    def parsed = map(_.parsed)
    
    /** Finds separators for any lines that haven't already been parsed; allow any key on the left. */
    def anykeyParsed = map(_.anykeyParsed)
    
    /** Glues together lines with continuation pattern `...` or `-->` at same position but with whitespace before.  Parsed lines only. */
    def compact = ap(ls => (Vector.empty[Line] /: ls){ (v,line) => 
      v.lastOption.flatMap(_ merge line) match {
        case Some(newline) => v.updated(v.length-1, newline)
        case None          => v :+ line
      }
    })
    
    /** Discards all empty lines after trimming whitespace (if you're going to `compact`, do it first, as `compact` is whitespace-aware!) */
    def trim = map(_.trimmed).ap(_.filter(! _.isWhite))
  }
  object Lines {
    import scala.util.Try
    
    val empty = new Lines(Vector.empty[Line])
    
    /** Reads a  bunch of text into `Lines`. */
    def read(xs: Vector[String]): Lines =
      new Lines(xs.zipWithIndex.map{ case (s,n) => Line.bare(s, n+1) })
      
    /** Optionally reads a file and parses it for arrows and dots, treating `blanks` successive blanks as different chunks (see `tokenize`) */
    def parsed(xs: Vector[String], blanks: Int): Vector[Lines] = 
      read(xs).tokenize(blanks).map(_.decomment.parsed.compact.trim).filter(_.lines.length > 0)
    
    /** Optionally reads and parses a file into a single chunk (no `tokenize`) */
    def parsed(xs: Vector[String]): Lines = read(xs).decomment.parsed.compact.trim
    
    /** Optionally reads and parses a file into a single chunk (no `tokenize`); allow any key on the left */
    def anykeyParsed(xs: Vector[String]): Lines = read(xs).decomment.anykeyParsed.compact.trim
  }

  /** `Test` encapsulates a single-line test.
    * @param line - The underlying line of text
    * @param params - The parameters (variables) used in this test
    * @param must - The flags that must be present to run this test
    * @param mustnt - If any of these flags are present, this test will not be run
    */
  case class Test(line: Line, params: Set[String], must: Set[String], mustnt: Set[String]) {
    import Test._
    private[this] def data = if (line.isSplit) line.right else line.left
    /** The methods required by this test (written between backticks in the test line) */
    lazy val methods = data.split('`').grouped(2).filter(_.length == 2).map(_(1)).toSet
    /** The code to run (with backticks elided) */
    lazy val code = data.filter(_ != '`')
    /** Checks to make sure every method is present as confirmed by `p` */
    def validateMethods(p: String => Boolean): Boolean = methods.forall(p)
    /** Checks to make sure every method is present as confirmed by `p` and removes those names if present in `unvisited` */
    def validateMethods(p: String => Boolean, unvisited: collection.mutable.HashSet[String]): Boolean = {
      val ans = validateMethods(p)
      if (ans) unvisited --= methods
      ans
    }
    /** Given an oracle `p` that answers if a flag is present, make sure all flags that must be there are and none are that mustn't be */
    def validateFlags(p: String => Boolean) = must.forall(p) && !mustnt.exists(p)
  }
  object Test {
    import scala.util._
    val FlagRegex = """(\p{Upper}[\p{Upper}\d]*)""".r
    val isFlag = (s: String) => s match { case FlagRegex(_) => true; case _ => false }
    
    /** Given a line from the file, try to parse out a single-line test, or produce an error message if it doesn't work. */
    def parseFrom(line: Line): Either[String, Test] = {
      if (!line.isSplit) Right(new Test(line, Set("x"), Set(), Set()))
      else if (line.sep != "...") Left(s"Wrong separator on line ${line.index}: ${line.sep}")
      else {
        val (params, rest1) = line.left.split("\\s+").filter(_.length > 0).partition(_.forall(_.isLower))
        val (must, rest2) = rest1.partition(isFlag)
        val (mustnt, uhoh) = rest2.partition(s => (s startsWith "!") && isFlag(s drop 1))
        if (uhoh.nonEmpty) Left(s"Syntax error in line ${line.index}: identifier neither parameter (lower-case) nor flag (upper): ${uhoh.mkString(" ")}")
        else Right(new Test(line, params.toSet | Set("x"), must.toSet, mustnt.map(_.drop(1)).toSet))
      }
    }
    
    def unapply(l: Line): Option[Test] = parseFrom(l).right.toOption
  }
  
  /** `Tests` encapsulates a bunch of single-line tests all of which require the same set of variables (ideally--this is not enforced at creation) 
    * @param params - The variables common to all tests (should be exhaustive)
    * @param tests - The individual tests
    */
  case class Tests(params: Set[String], tests: Vector[Test]) {
    @inline def underlying = tests
    @inline def isEmpty = tests.isEmpty
    /** The source code for all tests */
    lazy val codes = tests.map(_.code)
    def map(f: Test => Test) = new Tests(params, tests.map(f))
    def filter(p: Test => Boolean) = new Tests(params, tests.filter(p))
  }
  object Tests {
    import scala.util._
    
    /** Parse a whole bunch of lines into `Tests` grouped by common parameters, or error messages saying what went wrong */
    def parseFrom(lines: Lines): Either[Vector[String], Vector[Tests]] = {
      val tests = lines.underlying.map(Test.parseFrom)
      val wrong = tests.collect{ case Left(msg) => msg }
      if (wrong.nonEmpty) Left(wrong)
      else Right(
        tests.collect{ case Right(test) => test }.
          groupBy(x => x.params.toList.sorted).                    // Group same parameters
          map{ case (k,v) => k.mkString(s" ${k.length} ") -> v }.  // Convert parameters to nice form for sorting
          toVector.sortBy(_._1).map(_._2).                         // Put them in order
          map(ts => new Tests(ts.head.params, ts))
      )
    }
    
    /** Parse a bunch of text into `Tests` grouped by common parameters (or deliver error message(s)) */
    def read(xs: Vector[String]): Either[Vector[String], Vector[Tests]] = parseFrom(Lines.parsed(xs))
  }
  
  /** Flags that may be either positive or negative (extracted from a ReplaceParam) */
  case class Flags(pos: Set[String], neg: Set[String]) extends Function1[String, Boolean] {
    /** Check a string against the flags that are present (i.e. `pos`). */
    def apply(s: String) = pos contains s
    /** Add the flags on the RHS as long as they're not already mentioned */
    def :++(f: Flags) = Flags(pos | (f.pos diff neg), neg | (f.neg diff pos))
  }
  object Flags {
    def empty = Flags(Set(), Set())
    def parseFromLine(line: Line, explicit: Boolean = false): Either[String, Flags] = {
      // Don't assume the right side has been parsed apart, so rebuild and split it.
      val all = line.rights.mkString(" ").split("\\s+").filter(_.length > 0)
      val neg = all.filter(_ startsWith "-").map(_.drop(1)).toSet
      val pos = all.filter(_ startsWith "+").map(_.drop(1)).toSet
      val other = all.filter(x => !((x startsWith "+") | (x startsWith "-"))).toSet
      if (!line.isSplit) Left("Line ${line.index} was supposed to have a separator but none was found.")
      else if (other.nonEmpty && explicit) Left("Line ${line.index} is missing + or - on these flags: " + other.mkString(" "))
      else Right(Flags(pos | other, neg))
    }
  }
  
  /** A `line` that describes a replacement, plus the `key` to replace parsed from that line */
  sealed trait Replacer {
    def line: Line
    def key: String
  }
  /** Replacement signaled by a leading `@` symbol */
  sealed trait ReplaceAt extends Replacer {
    def at = "@"+key
  }
  /** A replacement with both a key and value parsed out, intended to be used as a definition not a textual replacement */
  case class ReplaceParam(line: Line, key: String, value: String) extends Replacer {}
  /** A replacement with both key and value parsed out, intended to be used on `@KEY` pattern */
  case class ReplaceSubst(line: Line, key: String, value: String) extends ReplaceAt {
    def subOn(s: String) = if (s contains at) s.split(at, -1).mkString(value) else s
  }
  /** A replacement with two alternatives; which is taken depends on a flag.
    * Signalled by the key ending with '?'.  Replacement should be specified by @KEY?FLAG.
    */
  case class ReplaceBranch(line: Line, key: String, yesValue: String, noValue: String) extends ReplaceAt {
    def subOn(s: String, flagLookup: String => Boolean): String = if (!(s contains at)) s else {
      val i = s.indexOf(at)
      if (i < 0) s
      else {
        val pre = s.substring(0,i)
        val post = s.substring(i+at.length)
        val flag = post.takeWhile(c => c.isUpper || c.isDigit || c == '_')
        val after = post.substring(flag.length)
        if (flag.length == 0 || !flag(0).isUpper) s
        else pre + (if (flagLookup(flag)) yesValue else noValue) + after
      }
    }
  }
  /** A set of replacements to apply, each in turn, on a `@KEY` pattern */
  case class ReplaceExpand(line: Line, key: String, values: Vector[String]) extends ReplaceAt {
    import scala.util._
    def allSubs(s: String): Either[String, Vector[String]] = 
      if (!(s contains at)) Left(s)
      else Right(values.map{v => s.split(at, -1).mkString(v)})
  }
  /** A replacement to apply to a pattern of form $KEY(stuff), where $KEY( becomes whatever is in `before` and ) becomes whatever is in `after` */
  case class ReplaceMacro(line: Line, key: String, before: String, after: String) extends Replacer {
    val rewrapper = (s: String) => before + s + after
    @tailrec final def macroOn(s: String, prefix: String = "", f: String => String = rewrapper): String = {
      val i = s.indexOfSlice(key)
      if (i < 0) prefix + s
      else pairedIndicesOf(s,i+key.length) match {
        case None => prefix + s
        case Some((i0, i1)) => macroOn(s drop i1+1, prefix + s.substring(0, i) + f(s.substring(i0+1, i1)), f)
      }
    }
    final def argsOf(s: String) = {
      val b = Vector.newBuilder[String]
      macroOn(s, "", t => { b += t; "" })
      b.result
    }
  }
  /** A fake replacement that carries a set of values to use as flags instead of actual replacements */
  case class ReplaceInfo(line: Line, key: String, values: Vector[String]) extends Replacer {}
  object Replacer {
    val ParamRegex = """^(\p{Lower}+)$""".r
    val SubstRegex = """^(\p{Upper}+\??)$""".r
    val MacroRegex = """^(\$\p{Upper}+)$""".r
    val InfoRegex = """^(\p{Lower}\p{Alpha}+)$""".r
    
    /** Parse a line into some sort of replacement pattern, if possible, or return an error message */
    def parseFrom(line: Line): Either[String, Replacer] = {
      if (!line.isSplit) Left(s"No arrow on line ${line.index}: ${line.whole}")
      else if (line.sep != "-->") Left(s"Line ${line.index} has wrong separator: ${line.sep}")
      else line.left match {
        case ParamRegex(name) => Right(ReplaceParam(line, name, line.right))
        case SubstRegex(name) => 
          if (name endsWith "?") delimSplit(line.right).toVector match {
            case Vector(y,n) => Right(ReplaceBranch(line, name, y, n))
            case _ => Left(s"Line ${line.index} should be a conditional but does not have two conditions.")
          }
          else Right(delimSplit(line.right).toVector match {
            case Vector(s) => ReplaceSubst(line, name, s)
            case v => ReplaceExpand(line, name, v)
          })
        case MacroRegex(name) => delimSplit(line.right).toVector match {
          case Vector(s) => 
            val i = s.indexOfSlice(" $ ")
            val j = if (i < 0) i else s.indexOfSlice(" $ ", i+3)
            if (i < 0) Left(s"Line ${line.index} is a macro but there is no substitution target ' $$ '")
            else if (j >= 0) Left(s"Line ${line.index} is a macro but there is more than one substitution target ' $$ '")
            else Right(ReplaceMacro(line, name, s take i+1, s drop i+2))
          case _ => Left(s"Line ${line.index} is a macro $name but does not have exactly one target pattern.")
        }
        case InfoRegex(name) => Right(ReplaceInfo(line, name, delimSplit(line.right).toVector))
        case x => Left(s"Key for line ${line.index} not one of (lower UPPER camelCase $$MACRO): $x")
      }
    }
  }
  
  /** A full set of replacements for various keywords and patterns with arguments.
    * @param params - Lower case keywords to be used as a definition or in a for loop
    * @param substs - Upper case keywords to be substituted literally
    * @param expands - Upper case keywords to be expanded into a set of every possibility
    * @param macros - Upper case keywords starting with `$` that are to be expanded as function calls
    * @param infos - Keywords with both upper and lower case, used as flags and directives not actual replacements
    */
  case class Replacements(
    flags: Flags,
    params: Map[String, ReplaceParam],
    substs: Map[String, ReplaceSubst],
    branches: Map[String, ReplaceBranch],
    expands: Map[String, ReplaceExpand],
    macros: Map[String, ReplaceMacro],
    infos: Map[String, ReplaceInfo],
    header: Option[Replacements.HeaderLine]
  ) {
    import Replacements.mergeMap
    
    /** Combines two sets of replacements into a unified whole (left side takes priority) */
    def merge(r: Replacements) = new Replacements(
      flags :++ r.flags,
      mergeMap(params, r.params),
      mergeMap(substs, r.substs),
      mergeMap(branches, r.branches),
      mergeMap(expands, r.expands),
      mergeMap(macros, r.macros),
      mergeMap(infos, r.infos),
      header orElse r.header
    )
    
    /** Apply the entire set of replacements to a string, generating a vector of all expansions.
      * To avoid infinite loops, replacements cannot be more than 7 layers deep.
      * If there are no alternatives in the expansion, a single string will be returned in the vector.
      */
    def apply(s: String): Either[String, Vector[String]] = {
      def inner(ss: Vector[String], depth: Int): Option[Vector[String]] = {
        val next = ss.flatMap{ s =>
          val s1 = if (!s.contains("$")) s else (s /: macros){ (si, m) => m._2.macroOn(si) }
          val s2 =
            if (!s1.contains("@")) s1
            else if (s1.contains("?")) (s1 /: branches){ (si, sb) => sb._2.subOn(si, flags) }
            else (s1 /: substs){ (si, sb) => sb._2.subOn(si) }
          val ss3 = 
            if (!s2.contains("@")) Vector(s2)
            else (Vector(s2) /: expands){ (sv, ex) => sv.flatMap{ si => ex._2.allSubs(si) match {
              case Left(sj) => Vector(sj)
              case Right(svj) => svj
            }}
          }
          ss3
        }
        val unchanged = next == ss
        if (unchanged) Some(next)
        else if (depth <= 0) None
        else inner(next, depth-1)
      }
      inner(Vector(s),7) match {
        case Some(vs) => Right(vs)
        case None => Left("Expansion of string did not terminate: " + s)
      }
    }

    def myLine = header.map(_.line.index.toString).getOrElse("unknown")
  }
  object Replacements {
    val empty = new Replacements(Flags.empty, Map(), Map(), Map(), Map(), Map(), Map(), None)
    
    sealed trait HeaderLine { def line: Line; def typ: String }
    final case class Wildcard(val line: Line, val typ: String) extends HeaderLine
    final case class Specific(val line: Line, val typ: String, val coll: String) extends HeaderLine {
      lazy val unparameterized = coll.takeWhile(_ != '[')
      private def replace(k: String, v: String) = k -> (new ReplaceSubst(line, k, v))
      def defaults: Replacements = empty.copy(substs = Map(
        replace("A", typ), replace("CC", coll), replace("CCN", unparameterized), replace("CCM", coll), replace("NAME", coll)
      ))
    }
  
    def mergeMap[V](a: Map[String, V], b: Map[String, V]): Map[String, V] =
      if (b.keys.forall(a contains _)) a
      else a ++ b.filterKeys(k => !(a contains k))
    
    def parseFrom(lines: Lines): Either[Vector[String], Replacements] = {
      val parsed = lines.underlying.map(Replacer.parseFrom)
      val errors = parsed.collect{ case Left(msg) => msg }
      if (errors.nonEmpty) Left(errors)
      else {
        val paramsWithFlags = parsed.collect{ case Right(rp: ReplaceParam) => rp.key -> rp }.toMap
        val params = paramsWithFlags //.filter(_._1 != "flags")
        val substs = parsed.collect{ case Right(rs: ReplaceSubst) => rs.key -> rs }.toMap
        val branches = parsed.collect{ case Right(rb: ReplaceBranch) => rb.key -> rb }.toMap
        val expands = parsed.collect{ case Right(re: ReplaceExpand) => re.key -> re }.toMap
        val macros = parsed.collect{ case Right(rm: ReplaceMacro) => rm.key -> rm }.toMap
        val infos = parsed.collect{ case Right(ri: ReplaceInfo) => ri.key -> ri }.toMap
        paramsWithFlags.get("flags").map(rp => Flags.parseFromLine(rp.line)).getOrElse(Right(Flags.empty)) match {
          case Right(flags) => Right(new Replacements(flags, params, substs, branches, expands, macros, infos, None))
          case Left(s) => Left(Vector(s))
        }
      }
    }
    
    def parseHead(line: Line): Either[String, HeaderLine] =
      if (line.isSplit) Left("Line "+line.index+" should be a header but parses as an arrow: " + line.whole)
      else line.whole.split("\\s+") match {
        case Array(l,r) =>
          if (r == "*") Right(Wildcard(line, l))
          else Right(Specific(line, l, r))
        case x => Left("Line "+line.index+" should be a header but does not contain two tokens (Type Collection):" + line.whole)
      }
    
    def parseFrom(lineses: Vector[Lines]): Either[Vector[String], Vector[Replacements]] = {
      val direct = lineses.map{ lines =>
        val first = lines.underlying.headOption.map(parseHead) match {
          case None => Left(Vector("Empty block of lines...somewhere?!"))
          case Some(Left(x)) => Left(Vector(x))
          case Some(Right(y)) => Right(y)
        }
        val rest = Replacements.parseFrom(lines.ap(_.drop(1)))
        (first, rest) match {
          case (Left(e1), Left(e2)) => Left(e1 ++ e2)
          case (Left(e1), _) => Left(e1)
          case (_, Left(e2)) => Left(e2)
          case (Right(auto), Right(given)) => Right(auto -> given)
        }
      }
      val errors = direct.collect{ case Left(es) => es }.flatten
      if (errors.nonEmpty) Left(errors)
      else {
        val wild = direct.collect{ case Right((w: Wildcard, x)) => (w.typ -> (w -> x)) }.toMap
        val spec = direct.collect{ case Right((s: Specific, x)) => s -> x }
        Right(spec.map{ case (header, mappings) => 
          (wild.get(header.typ) match {
            case Some((_, common)) => mappings merge common merge header.defaults
            case None              => mappings merge header.defaults
          }).copy(header = Some(header))
        })
      }
    }
    
    def read(xs: Vector[String]): Either[Vector[String], Vector[Replacements]] = 
      Replacements.parseFrom(Lines.parsed(xs, 2))
  }
 
  
  // Paired delimiters that we recognize
  val paired = "()[]{}<>".grouped(2).map(x => x(0) -> x(1)).toMap
  
  // Finds paired delimiters (matches outermost type only, nesting okay)
  // Will skip whitespace.  Otherwise delimiter must be bare (no preceding text),
  // or no delimiter is assumed and it just breaks on whitespace.
  def pairedIndicesOf(s: String, i: Int = 0): Option[(Int,Int)] = {
    if (i >= s.length) None
    else paired.get(s(i)) match {
      case None => 
        if (s(i).isWhitespace) pairedIndicesOf(s, i+1)
        else Some((i, s.indexWhere(_.isWhitespace, i+1)-1))
      case Some(r) =>
        val l = s(i)
        var depth = 1
        var j = i+1
        while (j < s.length && depth > 0) {
          val c = s(j)
          if (c==r) depth -= 1
          else if (c==l) depth += 1
          j += 1
        }
        if (depth==0) Some((i,j-1)) else None
    }
  }
  
  // Splits on whitespace but respects delimiters (outermost type only, but may be embedded in a symbol)
  // Will also respect single and double-quoted strings 'foo bar' and "baz quux".
  def delimSplit(s: String, from: Int = 0, found: Builder[String,Vector[String]] = Vector.newBuilder[String]): Seq[String] = {
    var i = from
    while (i < s.length && s(i).isWhitespace) i += 1
    if (i >= s.length) found.result
    else {
      var j = i
      while (j < s.length && !s(j).isWhitespace) {
        if (paired.contains(s(j)) || s(j) == '"' || s(j) == '\'') {
          var k = j+1
          val cl = s(j)
          val cr = paired.get(s(j)).getOrElse(cl)
          var depth = 1
          while (k < s.length && depth > 0) {
            val c = s(k)
            if (c==cr) depth -= 1
            else if (c==cl) depth += 1
            k += 1
          }
          j = k
        }
        else j += 1
      }
      found += s.substring(i,j)
      delimSplit(s, j, found)
    }
  }

}
