package com.heroku.sdk.gradle

import org.apache.commons.io.FileUtils
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Specification

class HerokuPluginTest extends Specification {

    @Rule
    TemporaryFolder temporaryFolder = new TemporaryFolder()

    File projectDir
    File buildFile
    String appName
    List<File> pluginClasspath

    GradleRunner with(String... tasks) {
        GradleRunner.create()
                .withPluginClasspath(pluginClasspath)
                .withProjectDir(projectDir)
                .withArguments(tasks)
    }

    String exec(String task) {
        StringBuffer sout = new StringBuffer(), serr = new StringBuffer()
        Process proc = task.execute()
        proc.consumeProcessOutput(sout, serr)
        proc.waitFor()
        return sout.toString()
    }

    boolean execCond(String task) {
        StringBuffer sout = new StringBuffer(), serr = new StringBuffer()
        Process proc = task.execute()
        proc.consumeProcessOutput(sout, serr)
        proc.waitFor()
        return proc.exitValue() == 0
    }

    def setup() {
        projectDir = temporaryFolder.root
        buildFile = temporaryFolder.newFile('build.gradle')
        File buildDir = temporaryFolder.newFolder('build')

        FileUtils.copyFile(
          new File("src/test/resources/sample-jar.jar"),
          new File(buildDir, "sample-jar.jar"))

        appName = "gradle-test-" + UUID.randomUUID().toString().substring(0,12);
        if (execCond("heroku create -n ${appName}")) {
          println("Created ${appName}")
        } else {
          throw RuntimeException("Failed to create app: ${appName}");
        }

        def pluginClasspathResource = getClass().classLoader.findResource("plugin-classpath.txt")
        if (pluginClasspathResource == null) {
            throw new IllegalStateException("Did not find plugin classpath resource, run `testClasses` build task.")
        }

        pluginClasspath = pluginClasspathResource.readLines()
                .collect { it.replace('\\', '\\\\') } // escape backslashes in Windows paths
                .collect { new File(it) }
    }

    def cleanup() {
      println(exec("heroku destroy ${appName} --confirm ${appName}"))
    }

    def 'fail when missing app name'() {
        given:
        buildFile << '''
            plugins {
                id 'com.heroku.sdk.heroku-gradle'
            }
        '''.stripIndent()

        when:
        BuildResult buildResult = with('deployHeroku').buildAndFail()

        then:
        buildResult.output.contains("Could not find app name: Git repo not found.")
    }

    def 'fail when app does not exist'() {
        given:
        buildFile << '''
            plugins {
                id 'com.heroku.sdk.heroku-gradle'
            }

            heroku {
                appName '87y9sadsf8dy7hfff32j'
                processTypes(
                    web: "java -jar build/sample-jar.jar"
                )
            }
        '''.stripIndent()

        when:
        BuildResult buildResult = with('deployHeroku').buildAndFail()

        then:
        buildResult.output.contains("Couldn't find that app.")
    }

    def 'success on happy path'() {
        given:
        buildFile << """
            plugins {
                id 'com.heroku.sdk.heroku-gradle'
            }

            heroku {
                appName '${ appName }'
                processTypes(
                    web: "java -jar build/sample-jar.jar"
                )
            }
        """.stripIndent()

        when:
        BuildResult buildResult = with('deployHeroku').build()

        then:
        buildResult.task(':deployHeroku').outcome == TaskOutcome.SUCCESS
        buildResult.output.contains("app: ${ appName }")
        buildResult.output.contains("including: build/")
        buildResult.output.contains("- success")
        buildResult.output.contains("Installing JDK 1.8")
        buildResult.output.contains("Done")

        exec("curl -L http://${appName}.herokuapp.com").contains("Hello from Java!")
    }

    def 'success with Procfile'() {
        given:
        temporaryFolder.newFile('Procfile') << """
            web: java -jar build/sample-jar.jar
        """.stripIndent()
        buildFile << """
            plugins {
                id 'com.heroku.sdk.heroku-gradle'
            }

            heroku {
                appName '${ appName }'
            }
        """.stripIndent()

        when:
        BuildResult buildResult = with('deployHeroku').build()

        then:
        buildResult.task(':deployHeroku').outcome == TaskOutcome.SUCCESS
        buildResult.output.contains("app: ${ appName }")
        buildResult.output.contains("including: build/")
        buildResult.output.contains("- success")
        buildResult.output.contains("Installing JDK 1.8")
        !buildResult.output.contains("No processTypes specified!")
        buildResult.output.contains("Done")

        exec("curl -L http://${appName}.herokuapp.com").contains("Hello from Java!")
    }

    def 'success with extra config'() {
        given:
        buildFile << """
            plugins {
                id 'com.heroku.sdk.heroku-gradle'
            }

            heroku {
                appName '${ appName }'
                includes = ['README.md']
            }
        """.stripIndent()

        when:
        BuildResult buildResult = with('deployHeroku').build()

        then:
        buildResult.task(':deployHeroku').outcome == TaskOutcome.SUCCESS
        buildResult.output.contains("app: ${ appName }")
        buildResult.output.contains("including: build/")
        buildResult.output.contains("- success")
        buildResult.output.contains("Installing JDK 1.8")
        buildResult.output.contains("Done")

        exec("heroku run cat ${exec("pwd").trim()}README.md -a ${appName}").contains("README.md")
    }
}
