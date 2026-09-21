package personalFinance.config

import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebConfig : WebMvcConfigurer {
    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/**")
            .allowedOriginPatterns("http://localhost:3000") // Allow React app's URL
            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
            .allowedHeaders("*")
            .allowCredentials(true)
    }
}

@Component
class RequestResponseLoggingFilter : Filter {
    private val logger = LoggerFactory.getLogger(RequestResponseLoggingFilter::class.java)

    override fun doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain) {
        val httpRequest = request as HttpServletRequest
        val httpResponse = response as HttpServletResponse

        val startTime = System.currentTimeMillis()
        logger.info("→ ${httpRequest.method} ${httpRequest.requestURI}")

        try {
            chain.doFilter(request, response)
        } catch (ex: Exception) {
            logger.error("Error processing request: ${httpRequest.method} ${httpRequest.requestURI}", ex)
            throw ex
        } finally {
            val duration = System.currentTimeMillis() - startTime
            logger.info("← ${httpRequest.method} ${httpRequest.requestURI} - Status ${httpResponse.status} (${duration}ms)")
        }
    }
}