package moni.config

import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.MalformedJwtException
import io.jsonwebtoken.UnsupportedJwtException
import io.jsonwebtoken.security.SignatureException
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import moni.auth.JwtAuth
import moni.user.UserService
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
            userId = try {
                jwtAuth.getUserIdFromJWT(jwt = jwt)
            } catch (e: ExpiredJwtException) {
                response.status = HttpServletResponse.SC_UNAUTHORIZED
                response.contentType = "application/json"
                response.writer.write("{\"message\":\"JWT token has expired\"}")
                return
            } catch (e: MalformedJwtException) {
                response.status = HttpServletResponse.SC_UNAUTHORIZED
                response.contentType = "application/json"
                response.writer.write("{\"message\":\"Malformed JWT token\"}")
                return
            } catch (e: UnsupportedJwtException) {
                response.status = HttpServletResponse.SC_UNAUTHORIZED
                response.contentType = "application/json"
                response.writer.write("{\"message\":\"Unsupported JWT token\"}")
                return
            } catch (e: SignatureException) {
                response.status = HttpServletResponse.SC_UNAUTHORIZED
                response.contentType = "application/json"
                response.writer.write("{\"message\":\"Invalid JWT signature\"}")
                return
            } catch (e: IllegalArgumentException) {
                response.status = HttpServletResponse.SC_UNAUTHORIZED
                response.contentType = "application/json"
                response.writer.write("{\"message\":\"Invalid JWT token\"}")
                return
            }
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