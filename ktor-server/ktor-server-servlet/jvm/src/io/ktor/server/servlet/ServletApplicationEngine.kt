/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.servlet

import io.ktor.server.application.*
import io.ktor.server.config.*
import io.ktor.server.config.ConfigLoader.Companion.load
import io.ktor.server.engine.*
import io.ktor.util.*
import org.slf4j.*
import javax.servlet.annotation.*
import kotlin.coroutines.*

/**
 * This servlet need to be installed into a servlet container
 */
@MultipartConfig
public open class ServletApplicationEngine : KtorServlet() {
    private val environment: ApplicationEngineEnvironment by lazy {
        val servletContext = servletContext
        val servletConfig = servletConfig

        servletContext.getAttribute(ApplicationEngineEnvironmentAttributeKey)?.let {
            return@lazy it as ApplicationEngineEnvironment
        }

        val parameterNames = (
            servletContext.initParameterNames?.toList().orEmpty() +
                servletConfig.initParameterNames?.toList().orEmpty()
            ).filter { it.startsWith("io.ktor") }.distinct()
        val parameters = parameterNames.map {
            it.removePrefix("io.ktor.") to
                (servletConfig.getInitParameter(it) ?: servletContext.getInitParameter(it))
        }

        val parametersConfig = MapApplicationConfig(parameters)
        val configPath = "ktor.config"
        val applicationIdPath = "ktor.application.id"

        val combinedConfig = parametersConfig
            .withFallback(ConfigLoader.load(parametersConfig.tryGetString(configPath)))

        val applicationId = combinedConfig.tryGetString(applicationIdPath) ?: "Application"

        applicationEngineEnvironment {
            config = combinedConfig
            log = LoggerFactory.getLogger(applicationId)
            classLoader = servletContext.classLoader
            rootPath = servletContext.contextPath ?: "/"
        }.apply {
            monitor.subscribe(ApplicationStarting) {
                it.receivePipeline.merge(enginePipeline.receivePipeline)
                it.sendPipeline.merge(enginePipeline.sendPipeline)
                it.receivePipeline.installDefaultTransformations()
                it.sendPipeline.installDefaultTransformations()
            }
        }
    }

    override val application: Application get() = environment.application

    override val logger: Logger get() = environment.log

    override val enginePipeline: EnginePipeline by lazy {
        servletContext.getAttribute(ApplicationEnginePipelineAttributeKey)?.let { return@lazy it as EnginePipeline }

        defaultEnginePipeline(environment).also {
            BaseApplicationResponse.setupSendPipeline(it.sendPipeline)
        }
    }

    override val upgrade: ServletUpgrade by lazy {
        if ("jetty" in (servletContext.serverInfo?.toLowerCasePreservingASCIIRules() ?: "")) {
            jettyUpgrade ?: DefaultServletUpgrade
        } else {
            DefaultServletUpgrade
        }
    }

    override val coroutineContext: CoroutineContext
        get() = super.coroutineContext + environment.parentCoroutineContext

    /**
     * Called by the servlet container when loading the servlet (on load)
     */
    override fun init() {
        environment.start()
        super.init()
    }

    override fun destroy() {
        environment.monitor.raise(ApplicationStopPreparing, environment)
        super.destroy()
        environment.stop()
    }

    public companion object {
        /**
         * An application engine environment instance key. It is not recommended to use unless you are writing
         * your own servlet application engine implementation
         */
        public const val ApplicationEngineEnvironmentAttributeKey: String =
            "_ktor_application_engine_environment_instance"

        /**
         * An application engine pipeline instance key. It is not recommended to use unless you are writing
         * your own servlet application engine implementation
         */
        public const val ApplicationEnginePipelineAttributeKey: String = "_ktor_application_engine_pipeline_instance"

        private val jettyUpgrade by lazy {
            try {
                Class.forName("io.ktor.server.jetty.internal.JettyUpgradeImpl").kotlin.objectInstance as ServletUpgrade
            } catch (t: Throwable) {
                null
            }
        }
    }
}
