package com.varabyte.kobweb.gradle.application.project.site

import com.varabyte.kobweb.gradle.application.extensions.visitAllChildren
import com.varabyte.kobweb.gradle.application.project.PackageUtils.prefixQualifiedPath
import com.varabyte.kobweb.gradle.application.project.PackageUtils.resolvePackageShortcut
import com.varabyte.kobweb.gradle.application.project.PsiUtils
import com.varabyte.kobweb.gradle.application.project.Reporter
import com.varabyte.kobweb.gradle.application.project.parseKotlinFile
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFileAnnotationList
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPackageDirective
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.psiUtil.isPublic
import java.io.File

private fun KtAnnotationEntry.getStringValue(index: Int): String? {
    val strExpr = valueArguments.getOrNull(index)?.getArgumentExpression() as? KtStringTemplateExpression ?: return null
    return strExpr.entries.firstOrNull()?.text
}

class SiteData {
    var app: AppEntry? = null
        internal set

    private val _pages = mutableListOf<PageEntry>()
    /** A collection of methods annotated with `@Page` and relevant metadata */
    val pages: List<PageEntry> = _pages

    private val _kobwebInits = mutableListOf<InitKobwebEntry>()
    /** A collection of methods annotated with `@InitKobweb` and relevant metadata */
    val kobwebInits: List<InitKobwebEntry> = _kobwebInits

    private val _silkInits = mutableListOf<InitSilkEntry>()
    /** A collection of methods annotated with `@InitSilk` and relevant metadata */
    val silkInits: List<InitSilkEntry> = _silkInits

    private val _silkStyleFqcns = mutableListOf<String>()
    /** The fully-qualified names of properties assigned to `ComponentStyle`s */
    val silkStyleFqcns: List<String> = _silkStyleFqcns

    private val _silkVariantFqcns = mutableListOf<String>()
    /** The fully-qualified names of properties assigned to `ComponentVariant`s */
    val silkVariantFqcns: List<String> = _silkVariantFqcns

    private class PageToProcess(
        val funName: String,
        val pkg: String,
        val slugFromFile: String,
        // See @Page annotation header docs for `routeOverride` behavior
        val routeOverride: String?
    )

    companion object {
        private fun processComponentStyle(
            file: File,
            filePackage: String,
            property: KtProperty,
            siteData: SiteData,
            reporter: Reporter
        ): Boolean {
            val propertyName = property.name ?: return false

            // Only top-level properties are allowed for now, so getting the fully qualified path is easy
            if (property.parent !is KtFile) {
                reporter.report("${file.absolutePath}: Not registering component style `val $propertyName`, as only top-level component styles are supported at this time")
                return false
            }

            return if (property.isPublic) {
                siteData._silkStyleFqcns.add(prefixQualifiedPath(filePackage, propertyName))
                true
            } else {
                reporter.report("${file.absolutePath}: Not registering component style `val $propertyName`, as it is not public")
                false
            }
        }

        /** Process a line like `val CustomStyle = ComponentStyle("custom") { ... }` */
        private fun processComponentStyle(
            file: File,
            filePackage: String,
            element: KtCallExpression,
            siteData: SiteData,
            reporter: Reporter
        ): Boolean {
            val property = element.parent as? KtProperty ?: return false
            return processComponentStyle(file, filePackage, property, siteData, reporter)
        }

        /** Process a line like `val CustomStyle = ComponentStyle.base("custom") { ... }` */
        private fun processComponentBaseStyle(
            file: File,
            filePackage: String,
            element: KtCallExpression,
            siteData: SiteData,
            reporter: Reporter
        ): Boolean {
            val qualifiedExpression = element.parent as? KtDotQualifiedExpression ?: return false
            if (qualifiedExpression.receiverExpression.text != "ComponentStyle") return false
            val property = qualifiedExpression.parent as? KtProperty ?: return false
            return processComponentStyle(file, filePackage, property, siteData, reporter)
        }

        private fun processComponentVariant(
            file: File,
            filePackage: String,
            property: KtProperty,
            siteData: SiteData,
            reporter: Reporter
        ): Boolean {
            val propertyName = property.name ?: return false

            // Only top-level properties are allowed for now, so getting the fully qualified path is easy
            if (property.parent !is KtFile) {
                reporter.report("${file.absolutePath}: Not registering component variant `val $propertyName`, as only top-level component variants are supported at this time")
                return false
            }

            return if (property.isPublic) {
                siteData._silkVariantFqcns.add(prefixQualifiedPath(filePackage, propertyName))
                true
            } else {
                reporter.report("${file.absolutePath}: Not registering component variant `val $propertyName`, as it is not public")
                false
            }
        }


        /** Process a line like `val CustomVariant = CustomStyle.addVariant("variant") { ... }` */
        private fun processComponentVariant(
            file: File,
            filePackage: String,
            element: KtCallExpression,
            siteData: SiteData,
            reporter: Reporter
        ): Boolean {
            val qualifiedExpression = element.parent as? KtDotQualifiedExpression ?: return false
            val property = qualifiedExpression.parent as? KtProperty ?: return false
            return processComponentVariant(file, filePackage, property, siteData, reporter)
        }

        /** Process a line like `val CustomVariant = CustomStyle.addBaseVariant("variant") { ... }` */
        private fun processComponentBaseVariant(
            file: File,
            filePackage: String,
            element: KtCallExpression,
            siteData: SiteData,
            reporter: Reporter
        ): Boolean {
            val qualifiedExpression = element.parent as? KtDotQualifiedExpression ?: return false
            val property = qualifiedExpression.parent as? KtProperty ?: return false
            return processComponentVariant(file, filePackage, property, siteData, reporter)
        }

        /**
         * @param group The group of this project, e.g. "org.example.mysite", required for prefixing relative package
         *   names (e.g. ".blog" -> "org.example.mysite.blog")
         * @param pagesPackage The relative package path that represents the root of the user's pages, e.g. ".pages"
         *   Annotated methods in source files not under this package will be ignored.
         * @param siteSources Kotlin jsMain source files that drive this site's frontend.
         */
        fun from(
            group: String,
            pagesPackage: String,
            siteSources: List<File>,
            reporter: Reporter,
        ): SiteData {
            val siteData = SiteData()
            val kotlinProject = PsiUtils.createKotlinProject()

            // fqPkg to subdir, e.g. "blog._2022._01" to "01"
            val packageMappings = mutableMapOf<String, String>()
            // We need to collect all package mappings before we start processing pages, so we store them in this
            // intermediate structure for a while
            val pagesToProcess = mutableListOf<PageToProcess>()

            // Qualify the pages package, e.g. ".pages", with this project's group, e.g. "com.site.pages"
            val qualifiedPagesPackage = resolvePackageShortcut(group, pagesPackage)

            siteSources.forEach { file ->
                val ktFile = kotlinProject.parseKotlinFile(file)

                var currPackage = ""
                var appSimpleName = APP_SIMPLE_NAME
                var packageMappingSimpleName = PACKAGE_MAPPING_SIMPLE_NAME
                var pageSimpleName = PAGE_SIMPLE_NAME
                var initKobwebSimpleName = INIT_KOBWEB_SIMPLE_NAME
                var initSilkSimpleName = INIT_SILK_SIMPLE_NAME

                // This value needs to be processed after all children are visited, because `currPackage`
                // gets set AFTER @file annotations. Wild.
                var packageMappingAnnotation: KtAnnotationEntry? = null
                ktFile.visitAllChildren { element ->
                    when (element) {
                        is KtPackageDirective -> {
                            currPackage = element.fqName.asString()
                        }
                        is KtFileAnnotationList -> {
                            val annotations = element.annotationEntries.toList()
                            annotations.forEach { entry ->
                                when (entry.shortName?.asString()) {
                                    packageMappingSimpleName ->
                                        packageMappingAnnotation = entry
                                }
                            }
                        }

                        is KtImportDirective -> {
                            // It's unlikely this will happen but catch the "import as" case,
                            // e.g. `import com.varabyte.kobweb.core.Page as MyPage`
                            when (element.importPath?.fqName?.asString()) {
                                APP_FQN -> {
                                    element.alias?.let { alias ->
                                        alias.name?.let { appSimpleName = it }
                                    }
                                }
                                PACKAGE_MAPPING_FQN -> {
                                    element.alias?.let { alias ->
                                        alias.name?.let { packageMappingSimpleName = it }
                                    }
                                }
                                PAGE_FQN -> {
                                    element.alias?.let { alias ->
                                        alias.name?.let { pageSimpleName = it }
                                    }
                                }
                                INIT_KOBWEB_FQN -> {
                                    element.alias?.let { alias ->
                                        alias.name?.let { initKobwebSimpleName = it }
                                    }
                                }
                                INIT_SILK_FQN -> {
                                    element.alias?.let { alias ->
                                        alias.name?.let { initSilkSimpleName = it }
                                    }
                                }
                            }
                        }
                        is KtNamedFunction -> {
                            val annotations = element.annotationEntries.toList()
                            annotations.forEach { entry ->
                                when (entry.shortName?.asString()) {
                                    appSimpleName -> {
                                        val appFqn = when {
                                            currPackage.isNotEmpty() -> "$currPackage.${element.name}"
                                            else -> element.name
                                        }
                                        appFqn?.let { siteData.app = AppEntry(it) }
                                    }
                                    pageSimpleName -> {
                                        if (!annotations.mapNotNull { it.shortName?.asString() }.contains("Composable")) {
                                            reporter.report("${file.absolutePath}: `fun ${element.name}` annotated with `@$pageSimpleName` must also be `@Composable`.")
                                        }

                                        val routeOverride = entry.getStringValue(0)?.takeIf { it.isNotBlank() }
                                        if (routeOverride?.startsWith("/") == true || currPackage.startsWith(qualifiedPagesPackage)) {
                                            // We cannot automatically infer the values of any except for the very last dynamic route parts,
                                            // because there's no guarantee they line up with the actual package. If the user is specifying the
                                            // whole route manually, then they should just name those parts explicitly.
                                            if (routeOverride == null || !routeOverride.substringBeforeLast("/", missingDelimiterValue = "").contains("{}")) {
                                                pagesToProcess.add(
                                                    PageToProcess(
                                                        funName = element.name!!.toString(),
                                                        pkg = currPackage,
                                                        slugFromFile = file.nameWithoutExtension.toLowerCase(),
                                                        routeOverride = routeOverride
                                                    )
                                                )
                                            }
                                            else {
                                                reporter.report("${file.absolutePath}: Skipped over `@$pageSimpleName fun ${element.name}`. Route override is invalid.")
                                            }
                                        } else {
                                            reporter.report("${file.absolutePath}: Skipped over `@$pageSimpleName fun ${element.name}`. It is defined under package `$currPackage` but must exist under `$qualifiedPagesPackage`")
                                        }
                                    }
                                    initKobwebSimpleName -> {
                                        element.name
                                            ?.let { name -> prefixQualifiedPath(currPackage, name) }
                                            ?.let { initFqn ->
                                                siteData._kobwebInits.add(
                                                    InitKobwebEntry(
                                                        initFqn,
                                                        acceptsContext = element.valueParameters.size == 1
                                                    )
                                                )
                                            }
                                    }
                                    initSilkSimpleName -> {
                                        element.name
                                            ?.let { name -> prefixQualifiedPath(currPackage, name) }
                                            ?.let { initFqn -> siteData._silkInits.add(InitSilkEntry(initFqn)) }
                                    }
                                }
                            }
                        }
                        is KtCallExpression -> {
                            when (element.calleeExpression?.text) {
                                "ComponentStyle" ->
                                    processComponentStyle(file, currPackage, element, siteData, reporter)
                                "base" -> processComponentBaseStyle(file, currPackage, element, siteData, reporter)
                                "addVariant" -> processComponentVariant(file, currPackage, element, siteData, reporter)
                                "addBaseVariant" ->
                                    processComponentBaseVariant(file, currPackage, element, siteData, reporter)
                            }
                        }
                    }
                }

                packageMappingAnnotation?.let { packageMappingAnnotation ->
                    if (currPackage.startsWith(qualifiedPagesPackage)) {
                        packageMappings[currPackage] = packageMappingAnnotation.getStringValue(0)!!.let { value ->
                            // {} is a special value which means infer from the current package,
                            // e.g. `a.b.pkg` -> `"{pkg}"`
                            if (value != "{}") value else "{${currPackage.substringAfterLast('.')}}"
                        }
                    }
                    else {
                        reporter.report("${packageMappingAnnotation.containingFile.virtualFile.path}: Skipped over `@file:$packageMappingSimpleName`. It is defined under package `$currPackage` but must exist under `$qualifiedPagesPackage`")
                    }
                }
            }

            for (pageToProcess in pagesToProcess) {
                val routeOverride = pageToProcess.routeOverride
                val slugPrefix = if (routeOverride != null && routeOverride.startsWith("/")) {
                    routeOverride.substringBeforeLast('/')
                } else {
                    // We have a bunch of potential package to URL mappings, which work on fully qualified packages, so
                    // we process each part of the package separately, going back to front. An example will help here.
                    //
                    // If we had the following mappings:
                    //
                    //   site.pages.blogs._2021._12 -> 12
                    //   site.pages.blogs._2021 -> 2021
                    //
                    // then we'd transform the following fully-qualified package by first building up a list of parts in
                    // reverse. So:
                    //
                    //   site.pages.blogs._2021._12.tutorials
                    //
                    // is processed like so (* means a mapping match was found):
                    //
                    //   site.pages.blogs._2021._12.tutorials ---> [tutorials]
                    //   site.pages.blogs._2021._12 (*)       ---> [12, tutorials]
                    //   site.pages.blogs._2021 (*)           ---> [2021, 12, tutorials]
                    //   site.pages.blogs                     ---> [blogs, 2021, 12, tutorials]
                    //   site.pages                           ---> [pages, blogs, 2021, 12, tutorials]
                    //   site                                 ---> [site, pages, blogs, 2021, 12, tutorials]
                    //
                    // At which point, we're done, and we can just join the final list together to a path:
                    //
                    //   site/pages/blogs/2021/12/tutorials
                    //
                    // (and we remove `site/pages` because it's part of the code, not the final URL)
                    run {
                        var pkg = pageToProcess.pkg
                        val transformedParts = mutableListOf<String>()
                        while (pkg.isNotEmpty()) {
                            transformedParts.add(0, packageMappings[pkg] ?: pkg.substringAfterLast('.'))
                            pkg = (pkg.takeIf { it.contains('.') } ?: "").substringBeforeLast('.')
                        }
                        transformedParts.joinToString(".")
                    }.removePrefix(qualifiedPagesPackage).replace('.', '/')
                }

                val prefixExtra = if (routeOverride != null && !routeOverride.startsWith("/") && routeOverride.contains("/")) {
                    "/" + routeOverride.substringBeforeLast("/")
                }
                else {
                    ""
                }

                val slug = if (routeOverride != null && routeOverride.last() != '/') {
                   routeOverride.substringAfterLast("/").let { value ->
                       // {} is a special value which means infer from the current file,
                       // e.g. `Slug.kt` -> `"{slug}"`
                       if (value != "{}") value else "{${pageToProcess.slugFromFile}}"
                   }
                } else {
                    pageToProcess.slugFromFile
                }.takeIf { it != "index" } ?: ""

                siteData._pages.add(
                    PageEntry(
                        "${pageToProcess.pkg}.${pageToProcess.funName}",
                        "$slugPrefix$prefixExtra/$slug"
                    )
                )
            }

            siteData._pages
                .groupBy { it.route }
                .filter { routeToPages -> routeToPages.value.size > 1 }
                .forEach { routeToPages ->
                    reporter.report("Route \"${routeToPages.key}\" was generated multiple times; only the one navigating to \"${routeToPages.value.first().fqn}()\" will be used.")
                    routeToPages.value.asSequence().drop(1).forEach { page -> siteData._pages.remove(page) }
                }

            return siteData
        }
    }
}