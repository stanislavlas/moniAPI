package personalFinance.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import personalFinance.auth.JwtAuth
import personalFinance.user.UserService
import java.util.*

private val EMAIL_VERIFICATION_EXEMPT_PATHS = setOf(
    "/api/auth/verify",
    "/api/auth/resend",
    "/api/auth/logout",
    "/api/auth/refresh",
    "/api/health",
)

@Component
class JwtRequestFilter(
    private val jwtAuth: JwtAuth,
    private val userService: UserService,
): OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val authorizationHeader = request.getHeader("Authorization")

        var userId: UUID? = null
        var jwt: String? = null

        if (authorizationHeader != null && authorizationHeader.startsWith("Bearer ")) {
            jwt = authorizationHeader.substring(7)
            userId = jwtAuth.getUserIdFromJWT(jwt = jwt)
        }

        if (userId != null && SecurityContextHolder.getContext().authentication == null) {
            val userDetails = userService.getUser(userId = userId) ?: run {
                filterChain.doFilter(request, response)
                return
            }

            if (jwtAuth.validateJWT(jwt!!, userDetails.userId)) {
                // Block unverified users from all protected routes
                if (!userDetails.emailVerified && request.requestURI !in EMAIL_VERIFICATION_EXEMPT_PATHS) {
                    response.status = HttpServletResponse.SC_FORBIDDEN
                    response.contentType = "application/json"
                    response.writer.write("{\"message\":\"EMAIL_NOT_VERIFIED\"}")
                    return
                }

                val usernamePasswordAuthenticationToken = UsernamePasswordAuthenticationToken(
                    userDetails, null, emptySet()
                )
                usernamePasswordAuthenticationToken.details = WebAuthenticationDetailsSource().buildDetails(request)
                SecurityContextHolder.getContext().authentication = usernamePasswordAuthenticationToken
            }
        }

        filterChain.doFilter(request, response)
    }
}