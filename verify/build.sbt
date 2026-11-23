// Compiles and tests every Scala snippet the plugin's skills quote, against the published anthropic4s.
//
// The skill references never contain code that is not in this project: `node scripts/snippets.mjs` copies each
// `// snip:start NAME` ... `// snip:end` region into the Markdown block that names it, and `--check` fails CI
// when the two disagree. Bump `anthropic4sVersion` when a new release ships, then fix whatever stops compiling.

val anthropic4sVersion = "1.0.0"

ThisBuild / scalaVersion := "2.13.18"
ThisBuild / scalacOptions ++= Seq("-deprecation", "-feature", "-Xfatal-warnings")

lazy val verify = project
  .in(file("."))
  .settings(
    name := "anthropic4s-plugin-verify",
    libraryDependencies ++= Seq(
      "io.github.zakariahere" %% "anthropic4s"           % anthropic4sVersion,
      "io.github.zakariahere" %% "anthropic4s-play-json" % anthropic4sVersion,
      "io.github.zakariahere" %% "anthropic4s-otel4s"    % anthropic4sVersion,
      "org.typelevel"         %% "otel4s-oteljava"       % "1.2.0",
      // Only the Play snippets need it; nothing here starts a Play application.
      "org.playframework"     %% "play"                  % "3.0.12" % Provided,
      "io.github.zakariahere" %% "anthropic4s-testkit"   % anthropic4sVersion % Test,
      "org.typelevel"         %% "munit-cats-effect"     % "2.2.1"            % Test,
      "org.slf4j"              % "slf4j-nop"             % "2.0.17"           % Test
    ),
    Test / fork := true
  )
