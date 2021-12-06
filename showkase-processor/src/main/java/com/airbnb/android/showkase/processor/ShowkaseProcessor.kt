package com.airbnb.android.showkase.processor

import com.airbnb.android.showkase.annotation.ShowkaseCodegenMetadata
import com.airbnb.android.showkase.annotation.ShowkaseColor
import com.airbnb.android.showkase.annotation.ShowkaseComposable
import com.airbnb.android.showkase.annotation.ShowkaseRoot
import com.airbnb.android.showkase.annotation.ShowkaseRootCodegen
import com.airbnb.android.showkase.annotation.ShowkaseScreenshot
import com.airbnb.android.showkase.annotation.ShowkaseTypography
import com.airbnb.android.showkase.processor.exceptions.ShowkaseProcessorException
import com.airbnb.android.showkase.processor.logging.ShowkaseExceptionLogger
import com.airbnb.android.showkase.processor.logging.ShowkaseValidator
import com.airbnb.android.showkase.processor.models.ShowkaseMetadata
import com.airbnb.android.showkase.processor.models.getShowkaseColorMetadata
import com.airbnb.android.showkase.processor.models.getShowkaseMetadata
import com.airbnb.android.showkase.processor.models.getShowkaseMetadataFromPreview
import com.airbnb.android.showkase.processor.models.getShowkaseTypographyMetadata
import com.airbnb.android.showkase.processor.models.toModel
import com.airbnb.android.showkase.processor.writer.ShowkaseExtensionFunctionsWriter
import com.airbnb.android.showkase.processor.writer.ShowkaseBrowserWriter
import com.airbnb.android.showkase.processor.writer.ShowkaseBrowserWriter.Companion.CODEGEN_AUTOGEN_CLASS_NAME
import com.airbnb.android.showkase.processor.writer.ShowkaseCodegenMetadataWriter
import com.airbnb.android.showkase.processor.writer.ShowkaseScreenshotTestWriter
import com.google.auto.service.AutoService
import javax.annotation.processing.AbstractProcessor
import javax.annotation.processing.Filer
import javax.annotation.processing.Messager
import javax.annotation.processing.ProcessingEnvironment
import javax.annotation.processing.Processor
import javax.annotation.processing.RoundEnvironment
import javax.annotation.processing.SupportedSourceVersion
import javax.lang.model.SourceVersion
import javax.lang.model.element.Element
import javax.lang.model.element.ExecutableElement
import javax.lang.model.element.TypeElement
import javax.lang.model.type.MirroredTypesException
import javax.lang.model.type.TypeMirror
import javax.lang.model.util.Elements
import javax.lang.model.util.Types

@AutoService(Processor::class) // For registering the service
@SupportedSourceVersion(SourceVersion.RELEASE_8) // to support Java 8
class ShowkaseProcessor: AbstractProcessor() {
    private lateinit var typeUtils: Types
    private lateinit var elementUtils: Elements
    private lateinit var filter: Filer
    private lateinit var messager: Messager
    private val logger = ShowkaseExceptionLogger()
    private val showkaseValidator = ShowkaseValidator()
    private lateinit var composableTypeMirror: TypeMirror
    private lateinit var textStyleTypeMirror: TypeMirror

    override fun init(processingEnv: ProcessingEnvironment) {
        super.init(processingEnv)
        typeUtils = processingEnv.typeUtils
        elementUtils = processingEnv.elementUtils
        filter = processingEnv.filer
        messager = processingEnv.messager
        composableTypeMirror = elementUtils
            .getTypeElement(COMPOSABLE_CLASS_NAME)
            .asType()
        textStyleTypeMirror = elementUtils
            .getTypeElement(TYPE_STYLE_CLASS_NAME)
            .asType()
    }

    override fun getSupportedAnnotationTypes(): MutableSet<String> = mutableSetOf(
        ShowkaseComposable::class.java.name,
        PREVIEW_CLASS_NAME,
        ShowkaseColor::class.java.name,
        ShowkaseTypography::class.java.name,
        ShowkaseRoot::class.java.name,
        ShowkaseScreenshot::class.java.name,
    )

    override fun process(
        annotations: MutableSet<out TypeElement>,
        roundEnvironment: RoundEnvironment
    ): Boolean {
        try {
            val componentMetadata = processComponentAnnotation(roundEnvironment)
            val colorMetadata = processColorAnnotation(roundEnvironment)
            val typographyMetadata = processTypographyAnnotation(roundEnvironment)

            processShowkaseMetadata(
                roundEnvironment = roundEnvironment, 
                componentMetadata = componentMetadata, 
                colorMetadata = colorMetadata,
                typographyMetadata = typographyMetadata
            )
        } catch (exception: ShowkaseProcessorException) {
            logger.logErrorMessage("${exception.message}")
        }

        if (roundEnvironment.processingOver()) {
            logger.publishMessages(messager)
        }
        return false
    }

    private fun processComponentAnnotation(roundEnvironment: RoundEnvironment)
            : Set<ShowkaseMetadata.Component> {
        val showkaseComposablesMetadata = processShowkaseAnnotation(roundEnvironment)
        val previewComposablesMetadata = processPreviewAnnotation(roundEnvironment)
        return (showkaseComposablesMetadata + previewComposablesMetadata)
            .dedupeAndSort()
            .toSet()
    }

    private fun processShowkaseAnnotation(roundEnvironment: RoundEnvironment): Set<ShowkaseMetadata.Component> {
        val previewParameterTypeElement =
            elementUtils.getTypeElement(PREVIEW_PARAMETER_CLASS_NAME)
        previewParameterTypeElement?.let { previewParameter -> 
            return roundEnvironment.getElementsAnnotatedWith(ShowkaseComposable::class.java)
                .mapNotNull { element ->
                    showkaseValidator.validateComponentElement(
                        element, composableTypeMirror, typeUtils,
                        ShowkaseComposable::class.java.simpleName,
                        previewParameterTypeElement.asType()
                    )
                    getShowkaseMetadata(
                        element = element as ExecutableElement,
                        elementUtil = elementUtils,
                        typeUtils = typeUtils,
                        showkaseValidator = showkaseValidator,
                        previewParameterTypeMirror = previewParameter.asType()
                    )
                }.toSet()
        } ?: return setOf()
    }
        

    private fun processPreviewAnnotation(roundEnvironment: RoundEnvironment): Set<ShowkaseMetadata.Component> {
        val previewTypeElement = elementUtils.getTypeElement(PREVIEW_CLASS_NAME)
        val previewParameterTypeElement = 
            elementUtils.getTypeElement(PREVIEW_PARAMETER_CLASS_NAME)
        previewTypeElement?.let {
            return roundEnvironment.getElementsAnnotatedWith(previewTypeElement).mapNotNull { element ->
                showkaseValidator.validateComponentElement(element, composableTypeMirror, typeUtils,
                    previewTypeElement.simpleName.toString(), previewParameterTypeElement.asType())
                getShowkaseMetadataFromPreview(
                    element = element as ExecutableElement, 
                    elementUtil = elementUtils, 
                    typeUtils = typeUtils, 
                    previewTypeMirror = previewTypeElement.asType(),
                    previewParameterTypeMirror = previewParameterTypeElement.asType(), 
                    showkaseValidator = showkaseValidator
                )
            }.toSet()
        } ?: return setOf()
    }

    private fun writeMetadataFile(uniqueComposablesMetadata: Set<ShowkaseMetadata>) {
        ShowkaseCodegenMetadataWriter(processingEnv).apply {
            generateShowkaseCodegenFunctions(uniqueComposablesMetadata, typeUtils)
        }
    }

    private fun Collection<ShowkaseMetadata.Component>.dedupeAndSort() = this.distinctBy {
        // It's possible that a composable annotation is annotated with both Preview & 
        // ShowkaseComposable(especially if we add more functionality to Showkase and they diverge
        // in the customizations that they offer). In that scenario, its important to dedupe the
        // composables as they will be processed across both the rounds. We first ensure that
        // only distict method's are passed onto the next round. We do this by deduping on 
        // the combination of packageName, the wrapper class when available(otherwise it 
        // will be null) & the methodName.
        "${it.packageName}_${it.enclosingClass}_${it.elementName}"
    }
        .distinctBy {
            // We also ensure that the component groupName and the component name are unique so 
            // that they don't show up twice in the browser app. 
            "${it.showkaseName}_${it.showkaseGroup}_${it.showkaseStyleName}"
        }
        .sortedBy {
            "${it.packageName}_${it.enclosingClass}_${it.elementName}"
        }

    private fun processColorAnnotation(roundEnvironment: RoundEnvironment) =
        roundEnvironment.getElementsAnnotatedWith(ShowkaseColor::class.java).map { element ->
            showkaseValidator.validateColorElement(element, ShowkaseColor::class.java.simpleName)
            getShowkaseColorMetadata(element, elementUtils, typeUtils, showkaseValidator)
        }.toSet()
    
    private fun processTypographyAnnotation(roundEnvironment: RoundEnvironment) = 
        roundEnvironment.getElementsAnnotatedWith(ShowkaseTypography::class.java).map { element ->
            showkaseValidator.validateTypographyElement(element, 
                ShowkaseTypography::class.java.simpleName, textStyleTypeMirror, typeUtils)
            getShowkaseTypographyMetadata(element, elementUtils, typeUtils, showkaseValidator)
        }.toSet()

    private fun processShowkaseMetadata(
        roundEnvironment: RoundEnvironment,
        componentMetadata: Set<ShowkaseMetadata.Component>,
        colorMetadata: Set<ShowkaseMetadata>,
        typographyMetadata: Set<ShowkaseMetadata>
    ) {
        // Showkase root annotation
        val rootElement = getShowkaseRootElement(roundEnvironment)

        // Showkase test annotation
        val screenshotTestElement = getShowkaseScreenshotTestElement(roundEnvironment)

        var showkaseProcessorMetadata = ShowkaseProcessorMetadata()

        // If root element is not present in this module, it means that we only need to write
        // the metadata file for this module so that the root module can use this info to
        // include the composables from this module into the final codegen file.
        writeMetadataFile(componentMetadata + colorMetadata + typographyMetadata)

        if (rootElement != null) {
            // This is the module that should aggregate all the other metadata files and
            // also use the showkaseMetadata set from the current round to write the final file.
            showkaseProcessorMetadata =
                writeShowkaseFiles(rootElement, componentMetadata, colorMetadata, typographyMetadata)
        }

        if (screenshotTestElement != null) {
            // Generate screenshot test file if ShowkaseScreenshotTest is present in the root module
            writeScreenshotTestFiles(screenshotTestElement, rootElement, showkaseProcessorMetadata)
        }
    }

    private fun getShowkaseRootElement(roundEnvironment: RoundEnvironment): Element? {
        val showkaseRootElements =
            roundEnvironment.getElementsAnnotatedWith(ShowkaseRoot::class.java)
        showkaseValidator.validateShowkaseRootElement(showkaseRootElements, elementUtils, typeUtils)
        val rootElement = showkaseRootElements.singleOrNull()
        return rootElement
    }

    private fun getShowkaseScreenshotTestElement(roundEnvironment: RoundEnvironment): Element? {
        val testElements =  roundEnvironment.getElementsAnnotatedWith(ShowkaseScreenshot::class.java)
        showkaseValidator.validateShowkaseTestElement(testElements, elementUtils, typeUtils)
        return testElements.singleOrNull()
    }

    private fun writeShowkaseFiles(
        rootElement: Element,
        componentMetadata: Set<ShowkaseMetadata.Component>,
        colorMetadata: Set<ShowkaseMetadata>,
        typographyMetadata: Set<ShowkaseMetadata>,
    ): ShowkaseProcessorMetadata {
        val generatedShowkaseMetadataOnClasspath =
            getShowkaseCodegenMetadataOnClassPath(elementUtils)
        val classpathComponentMetadata =
            generatedShowkaseMetadataOnClasspath.filterIsInstance<ShowkaseMetadata.Component>()
        val classpathColorMetadata =
            generatedShowkaseMetadataOnClasspath.filterIsInstance<ShowkaseMetadata.Color>()
        val classpathTypographyMetadata =
            generatedShowkaseMetadataOnClasspath.filterIsInstance<ShowkaseMetadata.Typography>()

        val allComponents = componentMetadata + classpathComponentMetadata
        val allColors = colorMetadata + classpathColorMetadata
        val allTypography = typographyMetadata + classpathTypographyMetadata

        writeShowkaseBrowserFiles(
            rootElement,
            allComponents,
            allColors,
            allTypography,
        )

        return ShowkaseProcessorMetadata(
            components = allComponents,
            colors = allColors,
            typography = allTypography
        )
    }

    private fun writeScreenshotTestFiles(
        screenshotTestElement: Element,
        rootElement: Element?,
        showkaseProcessorMetadata: ShowkaseProcessorMetadata,
    ) {
        val testClassName = screenshotTestElement.simpleName.toString()
        val screenshotTestPackageName = elementUtils.getPackageOf(screenshotTestElement).qualifiedName.toString()

        // Parse the showkase root class that was specified in @ShowkaseScreenshot
        val specifiedRootClassTypeMirror = getSpecifiedRootTypeElement(screenshotTestElement)
        val specifiedRootClassTypeElement = typeUtils.asElement(specifiedRootClassTypeMirror) as TypeElement

        // Get the package of the specified root module. We need this to ensure that we use the
        // Showkase.getMetadata metadata from that package.
        val rootModulePackageName = elementUtils.getPackageOf(specifiedRootClassTypeElement).qualifiedName.toString()

        val showkaseTestMetadata = if (rootElement != null &&
            specifiedRootClassTypeElement.simpleName.toString() == rootElement.simpleName.toString()
        ) {
            // If the specified root element is the being processed in the current processing round,
            // use it directly instead of looking for it in the class path. This is because it won't
            // be availabe in the classpath just yet.
            val (_, showkaseMetadataWithoutParameterList) =
                showkaseProcessorMetadata.components.filterIsInstance<ShowkaseMetadata.Component>()
                    .partition {
                        it.previewParameter != null
                    }
            ShowkaseTestMetadata(
                componentsSize = showkaseMetadataWithoutParameterList.size,
                showkaseProcessorMetadata.colors.size,
                showkaseProcessorMetadata.typography.size,
            )
        } else if (getShowkaseRootCodegenOnClassPath(elementUtils, specifiedRootClassTypeElement) != null) {
            // Else if we were able to find the specified root element in the classpath, we will use
            // the metadata from there instead.
            val showkaseRootCodegenAnnotation =
                getShowkaseRootCodegenOnClassPath(elementUtils, specifiedRootClassTypeElement)!!
            ShowkaseTestMetadata(
                componentsSize = showkaseRootCodegenAnnotation.numComposablesWithoutPreviewParameter,
                colorsSize = showkaseRootCodegenAnnotation.numColors,
                typographySize = showkaseRootCodegenAnnotation.numTypography
            )
        } else {
            throw ShowkaseProcessorException("Showkase was not able to find the root class that you" +
                    "passed to @ShowkaseScreenshot. Make sure that you have configured Showkase correctly.")
        }

        writeShowkaseScreenshotTestFile(
            // We only handle composables without preview parameter for screenshots. This is because
            // there's no way to get information about how many previews are dynamically generated using
            // preview parameter as it happens on run time and our codegen doesn't get enough information
            // to be able to predict how many extra composables the preview parameters extrapolate to.
            // TODO(vinaygaba): Add screenshot testing support for composabable with preview parameters as well
            showkaseTestMetadata.componentsSize,
            showkaseTestMetadata.colorsSize,
            showkaseTestMetadata.typographySize,
            screenshotTestPackageName,
            rootModulePackageName,
            testClassName,
        )
    }

    private fun getSpecifiedRootTypeElement(screenshotTestElement: Element): TypeMirror? {
        val showkaseScreenshotAnnotation =
            screenshotTestElement.getAnnotation(ShowkaseScreenshot::class.java)
        return try {
            showkaseScreenshotAnnotation.rootShowkaseClass
            listOf()
        } catch (mte: MirroredTypesException) {
            mte.typeMirrors
        }.firstOrNull()
    }

    private fun getShowkaseCodegenMetadataOnClassPath(elementUtils: Elements): Set<ShowkaseMetadata> {
        val showkaseGeneratedPackageElement = elementUtils.getPackageElement(CODEGEN_PACKAGE_NAME)
        return showkaseGeneratedPackageElement.enclosedElements
            .flatMap { it.enclosedElements }
            .mapNotNull { element ->
                val codegenMetadataAnnotation =
                    element.getAnnotation(ShowkaseCodegenMetadata::class.java)
                when {
                    codegenMetadataAnnotation == null -> null
                    else -> element to codegenMetadataAnnotation
                }
            }
            .map { it.second.toModel(it.first) }
            .toSet()
    }

    private fun getShowkaseRootCodegenOnClassPath(
        elementUtils: Elements,
        specifiedRootClassTypeElement: TypeElement
    ): ShowkaseRootCodegen? {
        val showkaseRootClassElement =
            elementUtils.getTypeElement("${specifiedRootClassTypeElement.qualifiedName}$CODEGEN_AUTOGEN_CLASS_NAME")

        return showkaseRootClassElement.getAnnotation(ShowkaseRootCodegen::class.java)
    }

    private fun writeShowkaseBrowserFiles(
        rootElement: Element,
        componentsMetadata: Set<ShowkaseMetadata.Component>,
        colorsMetadata: Set<ShowkaseMetadata>,
        typographyMetadata: Set<ShowkaseMetadata>,
    ) {
        if (componentsMetadata.isEmpty() && colorsMetadata.isEmpty() && typographyMetadata.isEmpty()) return
        val rootModuleClassName = rootElement.simpleName.toString()
        val rootModulePackageName = elementUtils.getPackageOf(rootElement).qualifiedName.toString()
        showkaseValidator.validateShowkaseComponents(componentsMetadata)

        ShowkaseBrowserWriter(processingEnv).apply {
            generateShowkaseBrowserFile(
                componentsMetadata, 
                colorsMetadata, 
                typographyMetadata,
                rootModulePackageName, 
                rootModuleClassName
            )
        }

        ShowkaseExtensionFunctionsWriter(processingEnv).apply {
            generateShowkaseExtensionFunctions(
                rootModulePackageName = rootModulePackageName,
                rootModuleClassName = rootModuleClassName,
                rootElement = rootElement
            )
        }
    }

    @Suppress("LongParameterList")
    private fun writeShowkaseScreenshotTestFile(
        componentsSize: Int,
        colorsSize: Int,
        typographySize: Int,
        screenshotTestPackageName: String,
        rootModulePackageName: String,
        testClassName: String,
    ) {
        ShowkaseScreenshotTestWriter(processingEnv).apply {
            generateScreenshotTests(
                componentsSize,
                colorsSize,
                typographySize,
                screenshotTestPackageName,
                rootModulePackageName,
                testClassName
            )
        }
    }

    private data class ShowkaseProcessorMetadata(
        val components: Set<ShowkaseMetadata> = setOf(),
        val colors: Set<ShowkaseMetadata> = setOf(),
        val typography: Set<ShowkaseMetadata> = setOf(),
    )

    private data class ShowkaseTestMetadata(
        val componentsSize: Int,
        val colorsSize: Int,
        val typographySize: Int,
    )

    companion object {
        const val COMPOSABLE_CLASS_NAME = "androidx.compose.runtime.Composable"
        const val PREVIEW_CLASS_NAME = "androidx.compose.ui.tooling.preview.Preview"
        const val PREVIEW_PARAMETER_CLASS_NAME = "androidx.compose.ui.tooling.preview.PreviewParameter"
        const val TYPE_STYLE_CLASS_NAME = "androidx.compose.ui.text.TextStyle"

        // https://github.com/Kotlin/kotlin-examples/blob/master/gradle/kotlin-code-generation/
        // annotation-processor/src/main/java/TestAnnotationProcessor.kt
        const val KAPT_KOTLIN_DIR_PATH = "kapt.kotlin.generated"
        const val CODEGEN_PACKAGE_NAME = "com.airbnb.android.showkase"
    }
}

