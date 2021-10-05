/*
 * Copyright (C) 2017-2020 HERE Europe B.V.
 * Copyright (C) 2021 Bosch.IO GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

package org.ossreviewtoolkit.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.output.CliktHelpFormatter
import com.github.ajalt.clikt.output.HelpFormatter
import com.github.ajalt.clikt.parameters.options.associate
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.switch
import com.github.ajalt.clikt.parameters.options.versionOption
import com.github.ajalt.clikt.parameters.types.file

import java.io.File

import kotlin.system.exitProcess

import org.apache.logging.log4j.Level
import org.apache.logging.log4j.core.config.Configurator

import org.ossreviewtoolkit.cli.commands.*
import org.ossreviewtoolkit.model.OrtIssue
import org.ossreviewtoolkit.model.RuleViolation
import org.ossreviewtoolkit.model.Severity
import org.ossreviewtoolkit.model.config.LicenseFilenamePatterns
import org.ossreviewtoolkit.model.config.OrtConfiguration
import org.ossreviewtoolkit.utils.Environment
import org.ossreviewtoolkit.utils.ORT_CONFIG_DIR_ENV_NAME
import org.ossreviewtoolkit.utils.ORT_CONFIG_FILENAME
import org.ossreviewtoolkit.utils.ORT_DATA_DIR_ENV_NAME
import org.ossreviewtoolkit.utils.ORT_NAME
import org.ossreviewtoolkit.utils.Os
import org.ossreviewtoolkit.utils.PERFORMANCE
import org.ossreviewtoolkit.utils.expandTilde
import org.ossreviewtoolkit.utils.log
import org.ossreviewtoolkit.utils.ortConfigDirectory
import org.ossreviewtoolkit.utils.ortDataDirectory
import org.ossreviewtoolkit.utils.printStackTrace

/**
 * Helper class for mutually exclusive command line options of different types.
 */
sealed class GroupTypes {
    data class FileType(val file: File) : GroupTypes()
    data class StringType(val string: String) : GroupTypes()
}

/**
 * Helper class for collecting options that can be passed to subcommands.
 */
data class GlobalOptions(
    val config: OrtConfiguration,
    val forceOverwrite: Boolean
)

/**
 * Helper class to collect severity statistics.
 */
internal class SeverityStats(
    private val resolvedCounts: Map<Severity, Int>,
    private val unresolvedCounts: Map<Severity, Int>
) {
    companion object {
        fun createFromIssues(
            resolvedIssues: Collection<OrtIssue>,
            unresolvedIssues: Collection<OrtIssue>
        ) =
            SeverityStats(
                resolvedCounts = resolvedIssues.groupingBy { it.severity }.eachCount(),
                unresolvedCounts = unresolvedIssues.groupingBy { it.severity }.eachCount()
            )

        fun createFromRuleViolations(
            resolvedRuleViolations: Collection<RuleViolation>,
            unresolvedRuleViolations: Collection<RuleViolation>
        ) =
            SeverityStats(
                resolvedCounts = resolvedRuleViolations.groupingBy { it.severity }.eachCount(),
                unresolvedCounts = unresolvedRuleViolations.groupingBy { it.severity }.eachCount()
            )
    }

    /**
     * Get the resolved count for [severity].
     */
    fun getResolvedCount(severity: Severity) = resolvedCounts.getOrDefault(severity, 0)

    /**
     * Get the unresolved count for [severity].
     */
    fun getUnresolvedCount(severity: Severity) = unresolvedCounts.getOrDefault(severity, 0)

    /**
     * Count all unresolved severities above or equal to [threshold].
     */
    fun getUnresolvedCountWithThreshold(threshold: Severity) =
        unresolvedCounts.entries.sumOf { (severity, count) -> if (severity >= threshold) count else 0 }
}

/**
 * A helper function to print statistics about the severity [stats]. If there are severities equal to or greater than
 * [threshold], print an according note and throw a ProgramResult exception with [severeStatusCode].
 */
internal fun concludeSeverityStats(stats: SeverityStats, threshold: Severity, severeStatusCode: Int) {
    val resolvedHintCount = stats.getResolvedCount(Severity.HINT)
    val resolvedWarningCount = stats.getResolvedCount(Severity.WARNING)
    val resolvedErrorCount = stats.getResolvedCount(Severity.ERROR)

    println(
        "Found $resolvedErrorCount resolved error(s), $resolvedWarningCount resolved warning(s), " +
                "$resolvedHintCount resolved hint(s)."
    )

    val unresolvedHintCount = stats.getUnresolvedCount(Severity.HINT)
    val unresolvedWarningCount = stats.getUnresolvedCount(Severity.WARNING)
    val unresolvedErrorCount = stats.getUnresolvedCount(Severity.ERROR)

    println(
        "Found $unresolvedErrorCount unresolved error(s), $unresolvedWarningCount unresolved warning(s), " +
                "$unresolvedHintCount unresolved hint(s)."
    )

    val severeIssueCount = stats.getUnresolvedCountWithThreshold(threshold)

    if (severeIssueCount > 0) {
        println(
            "There are $severeIssueCount issue(s) with a severity equal to or greater than the $threshold threshold."
        )

        throw ProgramResult(severeStatusCode)
    }
}

/**
 * The entry point for the application with [args] being the list of arguments.
 */
fun main(args: Array<String>) {
    Os.fixupUserHomeProperty()
    OrtMain().main(args)
    exitProcess(0)
}

class OrtMain : CliktCommand(name = ORT_NAME, invokeWithoutSubcommand = true) {
    private val configFile by option("--config", "-c", help = "The path to a configuration file.")
        .convert { it.expandTilde() }
        .file(mustExist = true, canBeFile = true, canBeDir = false, mustBeWritable = false, mustBeReadable = true)
        .default(ortConfigDirectory.resolve(ORT_CONFIG_FILENAME))

    private val logLevel by option(help = "Set the verbosity level of log output.").switch(
        "--info" to Level.INFO,
        "--performance" to PERFORMANCE,
        "--debug" to Level.DEBUG
    ).default(Level.WARN)

    private val stacktrace by option(help = "Print out the stacktrace for all exceptions.").flag()

    private val configArguments by option(
        "-P",
        help = "Override a key-value pair in the configuration file. For example: " +
                "-P ort.scanner.storages.postgresStorage.schema=testSchema"
    ).associate()

    private val forceOverwrite by option(
        "--force-overwrite",
        help = "Overwrite any output files if they already exist."
    ).flag()

    private val helpAll by option(
        "--help-all",
        help = "Display help for all subcommands."
    ).flag()

    private val env = Environment()

    private inner class OrtHelpFormatter : CliktHelpFormatter(requiredOptionMarker = "*", showDefaultValues = true) {
        var headerShownBefore = false

        override fun formatHelp(
            prolog: String,
            epilog: String,
            parameters: List<HelpFormatter.ParameterHelp>,
            programName: String
        ) =
            buildString {
                // The header only needs to be shown for the root command, as for subcommands the header was already
                // shown by the root command's run(). However, only show it if it has not been shown before as part of
                // "--help-all" (note that we cannot safely access the "helpAll" variable here as it might not have been
                // initialized yet.)
                val isRootCommand = currentContext.invokedSubcommand == null
                if (isRootCommand && !headerShownBefore) {
                    appendLine(getOrtHeader(env.ortVersion))
                    headerShownBefore = true
                }

                appendLine(super.formatHelp(prolog, epilog, parameters, programName))
                appendLine()
                appendLine("* denotes required options.")
            }
    }

    init {
        context {
            expandArgumentFiles = false
            helpFormatter = OrtHelpFormatter()
        }

        subcommands(
            AdvisorCommand(),
            AnalyzerCommand(),
            ConfigCommand(),
            DownloaderCommand(),
            EvaluatorCommand(),
            NotifierCommand(),
            ReporterCommand(),
            RequirementsCommand(),
            ScannerCommand(),
            UploadCurationsCommand(),
            UploadResultToPostgresCommand(),
            UploadResultToSw360Command()
        )

        versionOption(
            version = env.ortVersion,
            names = setOf("--version", "-v"),
            help = "Show the version and exit.",
            message = { it }
        )
    }

    override fun run() {
        Configurator.setRootLevel(logLevel)

        log.debug { "Used command line arguments: ${currentContext.originalArgv.joinToString()}" }

        // Make the parameter globally available.
        printStackTrace = stacktrace

        // Make options available to subcommands and apply static configuration.
        val ortConfiguration = OrtConfiguration.load(configArguments, configFile)
        currentContext.findOrSetObject { GlobalOptions(ortConfiguration, forceOverwrite) }
        LicenseFilenamePatterns.configure(ortConfiguration.licenseFilePatterns)

        if (helpAll) {
            registeredSubcommands().forEach {
                println(it.getFormattedHelp())
            }
        } else {
            println(getOrtHeader(env.ortVersion))
        }
    }

    private fun getOrtHeader(version: String): String {
        val variables = mutableListOf(
            "$ORT_CONFIG_DIR_ENV_NAME = $ortConfigDirectory",
            "$ORT_DATA_DIR_ENV_NAME = $ortDataDirectory"
        )

        env.variables.entries.mapTo(variables) { (key, value) -> "$key = $value" }

        val commandName = currentContext.invokedSubcommand?.commandName
        val command = commandName?.let { " '$commandName'" }.orEmpty()

        val header = mutableListOf<String>()
        val maxMemInMib = env.maxMemory / (1024 * 1024)

        """
            ________ _____________________
            \_____  \\______   \__    ___/ the OSS Review Toolkit, version $version.
             /   |   \|       _/ |    |
            /    |    \    |   \ |    |    Running$command under Java ${env.javaVersion} on ${env.os} with
            \_______  /____|_  / |____|    ${env.processors} CPUs and a maximum of $maxMemInMib MiB of memory.
                    \/       \/
        """.trimIndent().lines().mapTo(header) { it.trimEnd() }

        if (variables.isNotEmpty()) {
            header += "Environment variables:"
            header += variables
        }

        return header.joinToString("\n", postfix = "\n")
    }
}
