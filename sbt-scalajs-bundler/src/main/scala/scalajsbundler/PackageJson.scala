package scalajsbundler

import sbt._

import scalajsbundler.util.JS

object PackageJson {

  /**
    * Write a package.json file defining the NPM dependencies of the application, plus the ones
    * required to do the bundling.
    *
    * @param log Logger
    * @param targetFile File to write into
    * @param npmDependencies NPM dependencies
    * @param npmDevDependencies NPM devDependencies
    * @param npmResolutions Resolutions to use in case of conflicting dependencies
    * @param fullClasspath Classpath (used to look for dependencies of Scala.js libraries this project depends on)
    * @param currentConfiguration Current configuration
    * @return The created package.json file
    */
  def write(
    log: Logger,
    targetFile: File,
    npmDependencies: Seq[(String, String)],
    npmDevDependencies: Seq[(String, String)],
    npmResolutions: Map[String, String],
    fullClasspath: Seq[Attributed[File]],
    currentConfiguration: Configuration,
    webpackVersion: String
  ): Unit = {
    val npmManifestDependencies = NpmDependencies.collectFromClasspath(fullClasspath)
    val dependencies =
      npmDependencies ++ (
        if (currentConfiguration == Compile) npmManifestDependencies.compileDependencies
        else npmManifestDependencies.testDependencies
      )
    val devDependencies =
      npmDevDependencies ++ (
        if (currentConfiguration == Compile) npmManifestDependencies.compileDevDependencies
        else npmManifestDependencies.testDevDependencies
      ) ++ Seq(
        "webpack" -> webpackVersion,
        "concat-with-sourcemaps" -> "1.0.4", // Used by the reload workflow
        "source-map-loader" -> "0.1.5" // Used by webpack when emitSourceMaps is enabled
      )

    val packageJson =
      JS.obj(
        "dependencies" -> JS.objStr(resolveDependencies(dependencies, npmResolutions, log)),
        "devDependencies" -> JS.objStr(resolveDependencies(devDependencies, npmResolutions, log))
      )
    log.debug("Writing 'package.json'")
    IO.write(targetFile, JS.toJson(packageJson))
    ()
  }

  /**
    * Resolves multiple occurrences of a dependency to a same package.
    *
    *  - If all the occurrences refer to the same version, pick this one ;
    *  - If they refer to different versions, pick the one defined in `resolutions` (or fail
    *    if there is no such resolution).
    *
    * @return The resolved dependencies
    * @param dependencies The dependencies to resolve
    * @param resolutions The resolutions to use in case of conflict (they will be ignored if there are no conflicts)
    * @param log Logger
    */
  def resolveDependencies(
    dependencies: Seq[(String, String)],
    resolutions: Map[String, String],
    log: Logger
  ): List[(String, String)] ={
    val resolvedDependencies =
      dependencies
        .groupBy { case (name, version) => name }
        .mapValues(_.map(_._2).distinct)
        .foldRight(List.empty[(String, String)]) { case ((name, versions), result) =>
          val resolvedDependency =
            versions match {
              case Seq(single) =>
                name -> single
              case _ =>
                val resolution =
                  resolutions.getOrElse(name, sys.error(s"Different versions of '$name' are required: $versions, but no “resolution” has been provided."))
                name -> resolution
            }
          resolvedDependency :: result
        }

    // Add a warning in case a resolution was defined but not used because the corresponding
    // dependency was not in conflict.
    val unusedResolutions =
      resolutions.filter { case (name, resolution) =>
        resolvedDependencies.exists { case (n, v) => n == name && v != resolution }
      }
    if (unusedResolutions.nonEmpty) {
      log.warn(s"Unused resolutions: $unusedResolutions")
    }

    log.debug(s"Resolved the following dependencies: $resolvedDependencies")

    resolvedDependencies
  }

}
