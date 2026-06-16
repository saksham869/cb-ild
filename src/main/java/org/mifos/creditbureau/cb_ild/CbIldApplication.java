package org.mifos.creditbureau.cb_ild;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableAsync
public class CbIldApplication {

    public static void main(String[] args) {
        SpringApplication.run(CbIldApplication.class, args);
    }

}
