package moni

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource

@SpringBootTest
@TestPropertySource(properties = ["jwt.secret=dGVzdC1zZWNyZXQtZm9yLXVuaXQtdGVzdHMtMzJieXQ="])
class MoniApplicationTests {

	@Test
	fun contextLoads() {
	}

}
