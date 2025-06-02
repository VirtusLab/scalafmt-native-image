import $ivy.`de.tototec::de.tobiasroeser.mill.vcs.version::0.4.1`
import $ivy.`io.github.alexarchambault.mill::mill-native-image::0.1.30`
import $ivy.`io.github.alexarchambault.mill::mill-native-image-upload:0.1.29`

import de.tobiasroeser.mill.vcs.version._
import io.github.alexarchambault.millnativeimage.NativeImage
import io.github.alexarchambault.millnativeimage.upload.Upload
import mill._
import mill.scalalib._

object Versions {
  def scalafmtVersion = "3.9.7"

  def scalaVersion = "2.13.16"

  def jline = "3.25.0"

  def coursier = "2.1.24"
}

trait ScalafmtNativeImageModule extends ScalaModule {
  override def scalaVersion = Versions.scalaVersion

  override def transitiveIvyDeps = T {
    super.transitiveIvyDeps()
      .map(_.exclude("org.jline" -> "jline-native"))
      .map(_.exclude("org.jline" -> "jline-reader"))
      .map(_.exclude("org.jline" -> "jline-terminal"))
      .map(_.exclude("org.jline" -> "jline-terminal-jna"))
  }

  def jlineDeps = Agg(
    ivy"org.jline:jline-native:${Versions.jline}",
    ivy"org.jline:jline-reader:${Versions.jline}",
    ivy"org.jline:jline-terminal:${Versions.jline}",
    ivy"org.jline:jline-terminal-jna:${Versions.jline}"
  )

  override def ivyDeps = super.ivyDeps() ++ jlineDeps
}

trait ScalafmtNativeImage extends ScalafmtNativeImageModule with NativeImage {
  def nativeImageClassPath = T {
    val origCp = runClasspath()
    val tmpDir = T.dest / "jars"
    os.makeDir.all(tmpDir)
    origCp.map { p =>
      val path = p.path
      val name = path.last
      // stripping the "--verbose" and other options from the native-image.properties in the scalafmt-cli JAR,
      // as GraalVM 22.2 doesn't accept these options in properties files anymore
      if (name.startsWith("scalafmt-cli_2.13-") && name.endsWith(".jar")) {
        import java.io.{ByteArrayOutputStream, FileOutputStream, InputStream}
        import java.util.zip.{ZipEntry, ZipFile, ZipOutputStream}
        import scala.jdk.CollectionConverters._
        val dest = tmpDir / name

        var zf: ZipFile = null
        var fos: FileOutputStream = null
        var zos: ZipOutputStream = null
        try {
          zf = new ZipFile(path.toIO)
          fos = new FileOutputStream(dest.toIO)
          zos = new ZipOutputStream(fos)
          val buf = Array.ofDim[Byte](64 * 1024)
          for (ent <- zf.entries.asScala) {
            val postProcess = ent.getName == "META-INF/native-image/org.scalafmt/scalafmt-cli/native-image.properties"

            var is: InputStream = null
            try {
              is = zf.getInputStream(ent)

              if (postProcess) {
                val ent0 = new ZipEntry(ent.getName)
                zos.putNextEntry(ent0)
                val baos = new ByteArrayOutputStream
                var read = -1
                while ( {
                  read = is.read(buf)
                  read >= 0
                }) {
                  if (read > 0)
                    baos.write(buf, 0, read)
                }
                val content = new String(baos.toByteArray, "UTF-8")
                val updatedContent =
                  content
                    .replace("--verbose \\\n", "")
                    .replace("--strict-image-heap \\\n", "")
                    .replace("-march=native \\\n", "")
                    .replace("-H:+UnlockExperimentalVMOptions \\\n", "")
                    .replace("-H:-UnlockExperimentalVMOptions", "")
                zos.write(updatedContent.getBytes("UTF-8"))
              }
              else {
                zos.putNextEntry(ent)
                var read = -1
                while ( {
                  read = is.read(buf)
                  read >= 0
                }) {
                  if (read > 0)
                    zos.write(buf, 0, read)
                }
              }
            } finally {
              if (is != null)
                is.close()
            }
          }
          zos.finish()
        } finally {
          if (zf != null) zf.close()
          if (zos != null) zos.close()
          if (fos != null) fos.close()
        }

        PathRef(dest)
      }
      else
        p
    }
  }

  def nativeImageOptions = T {
    super.nativeImageOptions() ++ Seq(
      "--no-fallback"
    )
  }

  def nativeImagePersist = System.getenv("CI") != null

  def nativeImageGraalVmJvmId = "graalvm-java17:22.2.0"

  def nativeImageName = "scalafmt"

  def ivyDeps = super.ivyDeps() ++ jlineDeps ++ Seq(
    ivy"org.scalameta::scalafmt-cli:${Versions.scalafmtVersion}".excludeOrg("org.jline")
  )

  def nativeImageMainClass = "org.scalafmt.cli.Cli"

  def nameSuffix = ""

  def copyToArtifacts(directory: String = "artifacts/") = T.command {
    val _ = Upload.copyLauncher(
      nativeImage().path,
      directory,
      "scalafmt",
      compress = true,
      suffix = nameSuffix
    )
  }
}

object native extends ScalafmtNativeImage

object `native-static` extends ScalafmtNativeImage {
  def nameSuffix = "-static"

  def buildHelperImage = T {
    os.proc("docker", "build", "-t", "scala-cli-base-musl:latest", ".")
      .call(cwd = os.pwd / "musl-image", stdout = os.Inherit)
    ()
  }

  def nativeImageDockerParams = T {
    buildHelperImage()
    Some(
      NativeImage.linuxStaticParams(
        "scala-cli-base-musl:latest",
        s"https://github.com/coursier/coursier/releases/download/v${Versions.coursier}/cs-x86_64-pc-linux.gz"
      )
    )
  }

  def writeNativeImageScript(scriptDest: String, imageDest: String = "") = T.command {
    buildHelperImage()
    super.writeNativeImageScript(scriptDest, imageDest)()
  }
}

object `native-mostly-static` extends ScalafmtNativeImage {
  def nameSuffix = "-mostly-static"

  def nativeImageDockerParams = Some(
    NativeImage.linuxMostlyStaticParams(
      "ubuntu:18.04", // TODO Pin that?
      s"https://github.com/coursier/coursier/releases/download/v${Versions.coursier}/cs-x86_64-pc-linux.gz"
    )
  )
}


def publishVersion = T {
  val state = VcsVersion.vcsState()
  if (state.commitsSinceLastTag > 0) {
    val versionOrEmpty = state.lastTag
      .filter(_ != "latest")
      .map(_.stripPrefix("v"))
      .flatMap { tag =>
        val idx = tag.lastIndexOf(".")
        if (idx >= 0) Some(tag.take(idx + 1) + (tag.drop(idx + 1).toInt + 1).toString + "-SNAPSHOT")
        else None
      }
      .getOrElse("0.0.1-SNAPSHOT")
    Some(versionOrEmpty)
      .filter(_.nonEmpty)
      .getOrElse(state.format())
  } else
    state
      .lastTag
      .getOrElse(state.format())
      .stripPrefix("v")
}

def upload(directory: String = "artifacts/") = T.command {
  val version = publishVersion()

  val path = os.Path(directory, os.pwd)
  val launchers = os.list(path).filter(os.isFile(_)).map { path =>
    path -> path.last
  }
  val ghToken = Option(System.getenv("UPLOAD_GH_TOKEN")).getOrElse {
    sys.error("UPLOAD_GH_TOKEN not set")
  }
  val (tag, overwriteAssets) =
    if (version.endsWith("-SNAPSHOT")) ("launchers", true)
    else ("v" + version, false)

  Upload.upload("virtuslab", "scalafmt-native-image", ghToken, tag, dryRun = false, overwrite = overwriteAssets)(launchers: _*)
}
