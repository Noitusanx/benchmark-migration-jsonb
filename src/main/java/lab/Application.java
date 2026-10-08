package lab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        try (var context=SpringApplication.run(Application.class,args)) {
            // CommandLineRunner has finished; closing also releases the connection pool.
        }
    }
}
