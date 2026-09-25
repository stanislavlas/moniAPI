package moni.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder

/**
 * Defines the PasswordEncoder bean in its own configuration class.
 * Keeping it separate from SecurityConfig breaks the circular dependency:
 *   SecurityConfig → JwtRequestFilter → UserService → PasswordEncoder
 * With PasswordEncoder in its own class, no cycle exists and @Lazy is not needed.
 */
@Configuration
class PasswordConfig {
    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()
}
