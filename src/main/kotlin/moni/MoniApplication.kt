package moni

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling
import java.util.*

@SpringBootApplication(exclude = [UserDetailsServiceAutoConfiguration::class])
@EnableScheduling
class MoniApplication

fun main(args: Array<String>) {
	runApplication<MoniApplication>(*args)
}
