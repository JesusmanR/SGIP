package co.com.bspstore.sgip;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// Arranca el contexto completo: necesita la base con V1-V31 aplicadas y,
// por tanto, MySQL. Sin base se excluye con -DexcludedGroups=conBase.
@Tag("conBase")
@SpringBootTest
class BackendApplicationTests {

	@Test
	void contextLoads() {
	}

}
