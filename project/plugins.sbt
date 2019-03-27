logLevel := Level.Info

addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.3.18")
addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.9.0")
addSbtPlugin("com.typesafe.sbt" % "sbt-git" % "1.0.0")

libraryDependencies ++= Seq(
  "com.thesamet.scalapb" %% "compilerplugin" % "0.8.4",
  "com.spotify" % "docker-client" % "8.9.0"
)
