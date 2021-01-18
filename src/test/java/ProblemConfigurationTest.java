import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

class ProblemConfigurationTest {

    @Test
    void testMapper() throws IOException {

        byte [] json = Files.readAllBytes(Paths.get("C:/Users/iarmstrong" +
                "/Downloads/challenge-roadef-2020-master/challenge-roadef" +
                "-2020-master/example1.json"));
        ProblemConfiguration config =
                new ObjectMapper().readValue(json, ProblemConfiguration.class);

        System.out.println(config);
        assertEquals(3, config.getNumScenarios(1));
    }
}