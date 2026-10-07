//> using scala 3.9.0
//> using dep ch.epfl.scala::tasty-mima:1.4.1
// tasty-mima 1.4.1 pulls tasty-query 1.6.1, which can't read Scala 3.8+/3.9
// TASTy. tasty-query 1.9.0 can, and stays binary compatible within 1.x, so
// pin it explicitly. Drop this once a tasty-mima release depends on >= 1.9.0.
//> using dep ch.epfl.scala::tasty-query:1.9.0

// TASTy-compatibility check between a released artifact and the current
// build, via tasty-mima's core API (there is no scala-cli/Mill plugin; the
// only build integration is sbt-tasty-mima).
//
// MiMa (bin-compat-check.scala) checks that already-linked classfiles still
// link. This checks the other half of a Scala 3 library's contract: that
// TASTy compiled against the old release — notably `inline` / macro bodies
// that get re-typechecked when a user inlines them — still re-typechecks
// against the new one.
//
// Usage: scala-cli run .mima/tasty-compat-check.scala -- <oldJar> <oldClasspath> <newJar> <newClasspath>
//   oldJar        the previously released library JAR
//   oldClasspath  pathSeparator-joined dependency classpath of the old JAR
//   newJar        the freshly built library JAR
//   newClasspath  pathSeparator-joined dependency classpath of the new JAR
// Either classpath may include its JAR or not; the JDK's java.base is added.
//
// Optional per-repo file `.mima/tasty-private-packages` (read from the working
// directory, i.e. the repo root in CI; not synced from halotukozak-com/.github):
// one package name per line, `#` comments and blank lines ignored. Each one is
// handed to tasty-mima as an artifact-private package, so its definitions are
// no longer checked for TASTy compatibility. MiMa still checks them.
// Only list a package that no downstream TASTy can reference. Inline/macro
// calls nested in another library's `inline def` stay unexpanded in that
// library's TASTy and are expanded at the final call site, against whichever
// version of this library is on that classpath, so a macro's *expansion*
// never lands in someone else's TASTy. The unexpanded call still records what
// it refers to, though: inferred result types, signatures, default/using
// arguments, type aliases pointing into the package. A package that leaks
// through any of those is part of the TASTy contract, and listing it would
// hide real breaks.
//
// Exit code: 0 if TASTy-compatible, 2 if not. Anything else (1 from an
// uncaught exception, a dependency-resolution or compile failure in
// scala-cli, …) means the check itself couldn't run — callers must tell the
// two apart instead of treating every non-zero exit as an incompatibility.

import java.io.File
import java.net.URI
import java.nio.file.{Files, FileSystems, Path, Paths}

import scala.jdk.CollectionConverters.*

import tastymima.TastyMiMa
import tastymima.intf.Config

@main def tastyCompatCheck(oldJar: String, oldCp: String, newJar: String, newCp: String): Unit =
  val javaBase = FileSystems.getFileSystem(URI.create("jrt:/")).getPath("modules", "java.base")

  def classpath(jar: Path, cp: String): List[Path] =
    val deps = cp.split(File.pathSeparator).iterator.filter(_.nonEmpty).map(Paths.get(_).toAbsolutePath).toList
    (javaBase :: jar :: deps).distinct

  val oldEntry = Paths.get(oldJar).toAbsolutePath
  val newEntry = Paths.get(newJar).toAbsolutePath
  for jar <- List(oldEntry, newEntry) do require(Files.isRegularFile(jar), s"not a file: $jar")

  val privatePackagesFile = Paths.get(".mima/tasty-private-packages")
  val privatePackages =
    if !Files.exists(privatePackagesFile) then Nil
    else
      Files.readAllLines(privatePackagesFile).asScala.toList
        .map(_.takeWhile(_ != '#').trim)
        .filter(_.nonEmpty)
  if privatePackages.nonEmpty then
    println(s"[tasty-mima] treating as private (from $privatePackagesFile): ${privatePackages.mkString(", ")}")

  val config = new Config().withMoreArtifactPrivatePackages(privatePackages.asJava)

  val problems = new TastyMiMa(config)
    .analyze(classpath(oldEntry, oldCp), oldEntry, classpath(newEntry, newCp), newEntry)

  if problems.isEmpty then println("[tasty-mima] backward (TASTy built against the release vs the new JAR): OK")
  else
    println(s"[tasty-mima] backward (TASTy built against the release vs the new JAR): ${problems.size} problem(s)")
    problems.foreach(p => println(s"  - ${p.getDescription}"))
    sys.exit(2)
