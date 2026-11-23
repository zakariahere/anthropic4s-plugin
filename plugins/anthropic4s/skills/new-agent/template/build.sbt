val anthropic4sVersion = "1.0.0"

ThisBuild / scalaVersion := "2.13.18"
ThisBuild / scalacOptions ++= Seq("-deprecation", "-feature")

lazy val root = project
  .in(file("."))
  .settings(
    name := "my-agent",
    libraryDependencies ++= Seq(
      "io.github.zakariahere" %% "anthropic4s"         % anthropic4sVersion,
      "io.github.zakariahere" %% "anthropic4s-testkit" % anthropic4sVersion % Test,
      "org.typelevel"         %% "munit-cats-effect"   % "2.2.1"            % Test
    ),
    run / fork        := true, // so Ctrl+C stops the agent, and stdin works
    run / connectInput := true
  )
