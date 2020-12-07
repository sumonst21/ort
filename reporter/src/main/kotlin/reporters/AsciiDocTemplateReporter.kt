/*
 * Copyright (C) 2020 Bosch.IO GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
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

package org.ossreviewtoolkit.reporter.reporters

import java.io.File

import kotlin.io.path.createTempDirectory

import org.asciidoctor.Asciidoctor
import org.asciidoctor.AttributesBuilder
import org.asciidoctor.OptionsBuilder
import org.asciidoctor.SafeMode
import org.ossreviewtoolkit.reporter.Reporter
import org.ossreviewtoolkit.reporter.ReporterInput
import org.ossreviewtoolkit.reporter.utils.FreemarkerTemplateProcessor
import org.ossreviewtoolkit.utils.ORT_NAME
import org.ossreviewtoolkit.utils.safeDeleteRecursively

/**
 * A [Reporter] that creates PDF files using a combination of [Apache Freemarker][1] templates and [AsciiDoc][2]
 * with [AsciidoctorJ][3] as Java interface and [AsciidoctorJ PDF][4] as PDF file generator.
 * For each Freemarker template provided using the options described below a separate intermediate file is created
 * that can be processed by AsciidoctorJ. If no options are provided, the "disclosure_document" template is used, and if
 * security vulnerability information is available also the "vulerability_report" template.
 *
 * After the intermediate files are generated, they are processed by  AsciidoctorJ PDF.
 * A PDF theme can be handed over to AsciidoctorJ PDF in which properties like fonts or images displayed in the PDF can
 * be adjusted; see the [Theme Guide][5].
 * The path to this theme can be set in the options as described below.
 * Note that only one theme can be set that is used for all given templates. If no theme is given, a default built-in
 * theme of AsciidoctorJ PDF is used.
 *
 * This reporter supports the following options:
 * - *template.id*: A comma-separated list of IDs of templates provided by ORT. Currently, the "disclosure_document" and
 *                  "vulerability_report" templates are available.
 * - *template.path*: A comma-separated list of paths to template files provided by the user.
 * - *backend*: The name of the AsciiDoc backend to use, like "html". Defaults to "pdf". As a special case, the "adoc"
 *              fake backend is used to indicate that no backend should be used but the AsciiDoc files should be kept.
 * - *pdf-theme.path*: A path to an AsciiDoc PDF theme file. Only used with the "pdf" backend.
 *
 * [1]: https://freemarker.apache.org
 * [2]: https://asciidoc.org/
 * [3]: https://github.com/asciidoctor/asciidoctorj
 * [4]: https://github.com/asciidoctor/asciidoctorj-pdf
 * [5]: https://github.com/asciidoctor/asciidoctor-pdf/blob/master/docs/theming-guide.adoc
 */
class AsciiDocTemplateReporter : Reporter {
    companion object {
        private const val ASCII_DOC_FILE_PREFIX = "AsciiDoc_"
        private const val ASCII_DOC_FILE_EXTENSION = "adoc"
        private const val ASCII_DOC_TEMPLATE_DIRECTORY = "asciidoc"

        private const val DISCLOSURE_TEMPLATE_ID = "disclosure_document"
        private const val VULNERABILITY_TEMPLATE_ID = "vulnerability_report"

        private const val OPTION_BACKEND = "backend"
        private const val OPTION_PDF_THEME_PATH = "pdf-theme.path"

        private const val BACKEND_PDF = "pdf"
    }

    private val templateProcessor = FreemarkerTemplateProcessor(
        ASCII_DOC_FILE_PREFIX,
        ASCII_DOC_FILE_EXTENSION,
        ASCII_DOC_TEMPLATE_DIRECTORY
    )
    private val asciidoctor = Asciidoctor.Factory.create()

    override val reporterName = "AsciiDocTemplate"

    override fun generateReport(input: ReporterInput, outputDir: File, options: Map<String, String>): List<File> {
        val templateOptions = options.toMutableMap()
        val asciidoctorAttributes = AttributesBuilder.attributes()

        // Also see https://github.com/asciidoctor/asciidoctorj/issues/438 for supported backends.
        val backend = templateOptions.remove(OPTION_BACKEND) ?: BACKEND_PDF

        if (backend.equals(BACKEND_PDF, ignoreCase = true)) {
            templateOptions.remove(OPTION_PDF_THEME_PATH)?.let { themePath ->
                File(themePath).also {
                    require(it.isFile) { "Could not find pdf-theme file at '${it.absolutePath}'." }
                }

                asciidoctorAttributes.attribute("pdf-theme", themePath)
            }
        }

        if (!templateOptions.contains(FreemarkerTemplateProcessor.OPTION_TEMPLATE_PATH)) {
            templateOptions.putIfAbsent(FreemarkerTemplateProcessor.OPTION_TEMPLATE_ID, buildString {
                append(DISCLOSURE_TEMPLATE_ID)

                if (input.ortResult.getAdvisorResultContainers().isNotEmpty()) {
                    append(",$VULNERABILITY_TEMPLATE_ID")
                }
            })
        }

        val asciiDocTempDir = createTempDirectory("$ORT_NAME-asciidoc").toFile()
        val asciiDocFiles = templateProcessor.processTemplates(input, asciiDocTempDir, templateOptions)

        val outputFiles = mutableListOf<File>()

        if (backend.equals(ASCII_DOC_FILE_EXTENSION, ignoreCase = true)) {
            asciiDocFiles.forEach { file ->
                val outputFile = outputDir.resolve(file.name)
                file.copyTo(outputFile)
            }
        } else {
            asciiDocFiles.forEach { file ->
                val outputFile = outputDir.resolve("${file.nameWithoutExtension}.$backend")

                val asciidoctorOptions = OptionsBuilder.options()
                    .backend(backend)
                    .toFile(outputFile)
                    .attributes(asciidoctorAttributes)
                    .safe(SafeMode.UNSAFE)

                asciidoctor.convertFile(file, asciidoctorOptions)
                file.delete()

                outputFiles += outputFile
            }
        }

        asciiDocTempDir.safeDeleteRecursively()

        return outputFiles
    }
}