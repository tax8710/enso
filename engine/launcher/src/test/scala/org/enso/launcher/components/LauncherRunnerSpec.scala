package org.enso.launcher.components

import java.nio.file.{Files, Path}
import java.net.URI
import java.util.UUID
import org.enso.semver.SemVer
import org.enso.distribution.FileSystem.PathSyntax
import org.enso.editions.updater.EditionManager
import org.enso.runtimeversionmanager.config.GlobalRunnerConfigurationManager
import org.enso.runtimeversionmanager.runner._
import org.enso.runtimeversionmanager.test.RuntimeVersionManagerTest
import org.enso.launcher.project.ProjectManager
import org.enso.logger.TestLogger
import org.slf4j.event.Level

import org.enso.testkit.FlakySpec

import scala.concurrent.Future

/** We test integration of both the underlying [[Runner]] and the
  * [[LauncherRunner]] in a single suite.
  */
class LauncherRunnerSpec extends RuntimeVersionManagerTest with FlakySpec {
  private val defaultEngineVersion = SemVer.of(0, 0, 0, "default")

  private val fakeUri = URI.create("ws://test:1234/")

  def makeFakeRunner(
    cwdOverride: Option[Path]     = None,
    extraEnv: Map[String, String] = Map.empty
  ): LauncherRunner = {
    val (distributionManager, componentsManager, env) = makeManagers(extraEnv)
    val configurationManager =
      new GlobalRunnerConfigurationManager(
        componentsManager,
        distributionManager
      ) {
        override def defaultVersion: SemVer = defaultEngineVersion
      }
    val editionManager = EditionManager(distributionManager)
    val projectManager = new ProjectManager()
    val cwd            = cwdOverride.getOrElse(getTestDirectory)
    val runner =
      new LauncherRunner(
        projectManager,
        distributionManager,
        configurationManager,
        componentsManager,
        editionManager,
        env,
        Future.successful(Some(fakeUri))
      ) {
        override protected val currentWorkingDirectory: Path = cwd
      }
    runner
  }

  "Runner" should {
    "create a command from settings" in {
      val envOptions = "-Xfrom-env -Denv=env"
      val runner =
        makeFakeRunner(extraEnv = Map("ENSO_JVM_OPTS" -> envOptions))

      val runSettings = RunSettings(
        SemVer.of(0, 0, 0),
        Seq("arg1", "--flag2"),
        connectLoggerIfAvailable = true
      )
      val jvmOptions = Seq(("locally-added-options", "value1"))

      val enginePath =
        getTestDirectory / "test_data" / "dist" / "0.0.0"
      val runnerPath =
        (enginePath / "component" / "runner.jar").toAbsolutePath.normalize

      def checkCommandLine(command: Command): Unit = {
        val arguments     = command.command.tail
        val javaArguments = arguments.takeWhile(_ != "-jar")
        val appArguments  = arguments.dropWhile(_ != runnerPath.toString).tail
        javaArguments should contain("-Xfrom-env")
        javaArguments should contain("-Denv=env")
        javaArguments should contain("-Dlocally-added-options=value1")
        javaArguments should contain("-Dlocally-added-options=value1")
        javaArguments should contain("-Doptions-added-from-manifest=42")
        javaArguments should contain("-Xanother-one")

        val appCommandLine = appArguments.mkString(" ")

        appCommandLine shouldEqual s"--logger-connect $fakeUri arg1 --flag2"
        command.command.mkString(" ") should include(s"-jar $runnerPath")
      }

      runner.withCommand(
        runSettings,
        JVMSettings(
          useSystemJVM = true,
          jvmOptions   = jvmOptions,
          extraOptions = Seq()
        )
      ) { systemCommand =>
        systemCommand.command.head shouldEqual "java"
        checkCommandLine(systemCommand)
      }

      runner.withCommand(
        runSettings,
        JVMSettings(
          useSystemJVM = false,
          jvmOptions   = jvmOptions,
          extraOptions = Seq()
        )
      ) { managedCommand =>
        managedCommand.command.head should include("java")
        val javaHome =
          managedCommand.extraEnv.find(_._1 == "JAVA_HOME").value._2
        javaHome should include("graalvm-ce")
      }
    }

    "create project with name, default author (if specified) and additional arguments" in {
      val runner             = makeFakeRunner()
      val projectPath        = getTestDirectory / "project"
      val authorName         = "Author Name"
      val authorEmail        = "author@example.com"
      val additionalArgument = "additional arg"
      val runSettings = runner
        .newProject(
          path                = projectPath,
          name                = "ProjectName",
          engineVersion       = defaultEngineVersion,
          normalizedName      = None,
          projectTemplate     = None,
          authorName          = Some(authorName),
          authorEmail         = Some(authorEmail),
          additionalArguments = Seq(additionalArgument)
        )
        .get

      runSettings.engineVersion shouldEqual defaultEngineVersion
      runSettings.runnerArguments should contain(additionalArgument)
      val commandLine = runSettings.runnerArguments.mkString(" ")
      commandLine should include(
        s"--new ${projectPath.toAbsolutePath.normalize}"
      )
      commandLine should include("--new-project-name ProjectName")
      commandLine should include(s"--new-project-author-name $authorName")
      commandLine should include(s"--new-project-author-email $authorEmail")
    }

    "create project with name and module name" in {
      val runner         = makeFakeRunner()
      val projectPath    = getTestDirectory / "project"
      val normalizedName = "Project_Name"
      val runSettings = runner
        .newProject(
          path                = projectPath,
          name                = "ProjectName",
          engineVersion       = defaultEngineVersion,
          normalizedName      = Some(normalizedName),
          projectTemplate     = None,
          authorName          = None,
          authorEmail         = None,
          additionalArguments = Seq()
        )
        .get

      runSettings.engineVersion shouldEqual defaultEngineVersion
      val commandLine = runSettings.runnerArguments.mkString(" ")
      commandLine should include(
        s"--new ${projectPath.toAbsolutePath.normalize}"
      )
      commandLine should include("--new-project-name ProjectName")
      commandLine should include(
        s"--new-project-normalized-name $normalizedName"
      )
    }

    "warn when creating a project using a nightly version" taggedAs Flaky in {
      val runner         = makeFakeRunner()
      val projectPath    = getTestDirectory / "project2"
      val nightlyVersion = SemVer.of(0, 0, 0, "SNAPSHOT.2000-01-01")
      val (_, logs) = TestLogger.gather[Any, Runner](
        classOf[Runner], {
          runner
            .newProject(
              path                = projectPath,
              name                = "ProjectName2",
              engineVersion       = nightlyVersion,
              normalizedName      = None,
              projectTemplate     = None,
              authorName          = None,
              authorEmail         = None,
              additionalArguments = Seq()
            )
            .get
        }
      )
      assert(
        logs.exists(msg =>
          msg.level == Level.WARN && msg.msg.contains(
            "Consider using a stable version."
          )
        )
      )
    }

    "run repl with default version and additional arguments" in {
      val runner = makeFakeRunner()
      val runSettings = runner
        .repl(
          projectPath         = None,
          versionOverride     = None,
          additionalArguments = Seq("arg", "--flag"),
          logLevel            = Level.INFO,
          logMasking          = true
        )
        .get

      runSettings.engineVersion shouldEqual defaultEngineVersion
      runSettings.runnerArguments should (contain("arg") and contain("--flag"))
      runSettings.runnerArguments.mkString(" ") should
      (include("--repl") and not include s"--in-project")
    }

    "run repl in project context" in {
      val runnerOutside = makeFakeRunner()

      val version = SemVer.of(0, 0, 0, "repl-test")
      version should not equal defaultEngineVersion // sanity check

      val projectPath    = getTestDirectory / "project"
      val normalizedPath = projectPath.toAbsolutePath.normalize.toString
      newProject("test", projectPath, version)

      val outsideProject = runnerOutside
        .repl(
          projectPath         = Some(projectPath),
          versionOverride     = None,
          additionalArguments = Seq(),
          logLevel            = Level.INFO,
          logMasking          = true
        )
        .get

      outsideProject.engineVersion shouldEqual version
      outsideProject.runnerArguments.mkString(" ") should
      (include(s"--in-project $normalizedPath") and include("--repl"))

      val runnerInside = makeFakeRunner(Some(projectPath))
      val insideProject = runnerInside
        .repl(
          projectPath         = None,
          versionOverride     = None,
          additionalArguments = Seq(),
          logLevel            = Level.INFO,
          logMasking          = true
        )
        .get

      insideProject.engineVersion shouldEqual version
      insideProject.runnerArguments.mkString(" ") should
      (include(s"--in-project $normalizedPath") and include("--repl"))

      val overridden = SemVer.of(0, 0, 0, "overridden")
      val overriddenRun = runnerInside
        .repl(
          projectPath         = Some(projectPath),
          versionOverride     = Some(overridden),
          additionalArguments = Seq(),
          logLevel            = Level.INFO,
          logMasking          = true
        )
        .get

      overriddenRun.engineVersion shouldEqual overridden
      overriddenRun.runnerArguments.mkString(" ") should
      (include(s"--in-project $normalizedPath") and include("--repl"))
    }

    "run language server" in {
      val runner = makeFakeRunner()

      val version     = SemVer.of(0, 0, 0, "language-server-test")
      val projectPath = getTestDirectory / "project"
      newProject("test", projectPath, version)

      val options = LanguageServerOptions(
        rootId         = UUID.randomUUID(),
        interface      = "127.0.0.2",
        rpcPort        = 1234,
        secureRpcPort  = None,
        dataPort       = 4321,
        secureDataPort = None
      )
      val runSettings = runner
        .languageServer(
          options,
          contentRootPath     = projectPath,
          versionOverride     = None,
          additionalArguments = Seq("additional"),
          logLevel            = Level.INFO,
          logMasking          = true
        )
        .get

      runSettings.engineVersion shouldEqual version
      val commandLine = runSettings.runnerArguments.mkString(" ")
      commandLine should include(s"--interface ${options.interface}")
      commandLine should include(s"--rpc-port ${options.rpcPort}")
      commandLine should include(s"--data-port ${options.dataPort}")
      commandLine should include(s"--root-id ${options.rootId}")
      val normalizedPath = projectPath.toAbsolutePath.normalize.toString
      commandLine should include(s"--path $normalizedPath")
      runSettings.runnerArguments.lastOption.value shouldEqual "additional"

      val overridden = SemVer.of(0, 0, 0, "overridden")
      runner
        .languageServer(
          options,
          contentRootPath     = projectPath,
          versionOverride     = Some(overridden),
          additionalArguments = Seq(),
          logLevel            = Level.INFO,
          logMasking          = true
        )
        .get
        .engineVersion shouldEqual overridden
    }

    "run a project" in {
      val runnerOutside = makeFakeRunner()

      val version        = SemVer.of(0, 0, 0, "run-test")
      val projectPath    = getTestDirectory / "project"
      val normalizedPath = projectPath.toAbsolutePath.normalize.toString
      newProject("test", projectPath, version)

      val outsideProject = runnerOutside
        .run(
          path                = Some(projectPath),
          versionOverride     = None,
          additionalArguments = Seq(),
          logLevel            = Level.INFO,
          logMasking          = true
        )
        .get

      outsideProject.engineVersion shouldEqual version
      outsideProject.runnerArguments.mkString(" ") should
      include(s"--run $normalizedPath")

      val runnerInside = makeFakeRunner(Some(projectPath))
      val insideProject = runnerInside
        .run(
          path                = None,
          versionOverride     = None,
          additionalArguments = Seq(),
          logLevel            = Level.INFO,
          logMasking          = true
        )
        .get

      insideProject.engineVersion shouldEqual version
      insideProject.runnerArguments.mkString(" ") should
      include(s"--run $normalizedPath")

      val overridden = SemVer.of(0, 0, 0, "overridden")
      val overriddenRun = runnerInside
        .run(
          path                = Some(projectPath),
          versionOverride     = Some(overridden),
          additionalArguments = Seq(),
          logLevel            = Level.INFO,
          logMasking          = true
        )
        .get

      overriddenRun.engineVersion shouldEqual overridden
      overriddenRun.runnerArguments.mkString(" ") should
      include(s"--run $normalizedPath")

      assert(
        runnerOutside
          .run(
            path                = None,
            versionOverride     = None,
            additionalArguments = Seq(),
            logLevel            = Level.INFO,
            logMasking          = true
          )
          .isFailure,
        "Running outside project without providing any paths should be an error"
      )
    }

    "run a script outside of a project even if cwd is inside project" in {
      val version     = SemVer.of(0, 0, 0, "run-test")
      val projectPath = getTestDirectory / "project"
      val runnerInside =
        makeFakeRunner(cwdOverride = Some(projectPath))
      newProject("test", projectPath, version)

      val outsideFile    = getTestDirectory / "Main.enso"
      val normalizedPath = outsideFile.toAbsolutePath.normalize.toString
      Files.copy(
        projectPath / "src" / "Main.enso",
        outsideFile
      )

      val runSettings = runnerInside
        .run(
          path                = Some(outsideFile),
          versionOverride     = None,
          additionalArguments = Seq(),
          logLevel            = Level.INFO,
          logMasking          = true
        )
        .get

      runSettings.engineVersion shouldEqual defaultEngineVersion
      runSettings.runnerArguments.mkString(" ") should
      (include(s"--run $normalizedPath") and (not(include("--in-project"))))
    }

    "run a script inside of a project" in {
      val version               = SemVer.of(0, 0, 0, "run-test")
      val projectPath           = getTestDirectory / "project"
      val normalizedProjectPath = projectPath.toAbsolutePath.normalize.toString
      val runnerOutside         = makeFakeRunner()
      newProject("test", projectPath, version)

      val insideFile         = projectPath / "src" / "Main.enso"
      val normalizedFilePath = insideFile.toAbsolutePath.normalize.toString

      val runSettings = runnerOutside
        .run(
          path                = Some(insideFile),
          versionOverride     = None,
          additionalArguments = Seq(),
          logLevel            = Level.INFO,
          logMasking          = true
        )
        .get

      runSettings.engineVersion shouldEqual version
      runSettings.runnerArguments.mkString(" ") should
      (include(s"--run $normalizedFilePath") and
      include(s"--in-project $normalizedProjectPath"))
    }

    "get default version outside of project" in {
      val runner = makeFakeRunner()
      val (runSettings, whichEngine) = runner
        .version(useJSON = true)
        .get

      runSettings.engineVersion shouldEqual defaultEngineVersion
      runSettings.runnerArguments should
      (contain("--version") and contain("--json"))

      whichEngine shouldEqual WhichEngine.Default
    }

    "get project version inside of project" in {
      val version      = SemVer.of(0, 0, 0, "version-test")
      val projectPath  = getTestDirectory / "project"
      val name         = "Testname"
      val runnerInside = makeFakeRunner(cwdOverride = Some(projectPath))
      newProject(name, projectPath, version)
      val (runSettings, whichEngine) = runnerInside
        .version(useJSON = false)
        .get

      runSettings.engineVersion shouldEqual version
      runSettings.runnerArguments should
      (contain("--version") and not(contain("--json")))

      whichEngine shouldEqual WhichEngine.FromProject(name)
    }
  }
}
