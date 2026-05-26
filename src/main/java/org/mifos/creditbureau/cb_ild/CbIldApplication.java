package org.mifos.creditbureau.cb_ild;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CbIldApplication {

    public static void main(String[] args) {
        SpringApplication.run(CbIldApplication.class, args);
    }

}
