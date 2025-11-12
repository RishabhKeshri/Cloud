//Circuit Breaker 
/*Scenario

An OrderService calls a PaymentService that may fail under heavy load.
We use Resilience4j CircuitBreaker to stop repeated failing calls.*/
//Dependenices

<dependency>
  <groupId>io.github.resilience4j</groupId>
  <artifactId>resilience4j-spring-boot3</artifactId>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-web</artifactId>
</dependency>

//Payment Service port 8082
package com.example.payment;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.web.bind.annotation.*;
import java.util.Random;

@SpringBootApplication
@RestController
public class PaymentApp {
    Random random = new Random();
    public static void main(String[] args) { SpringApplication.run(PaymentApp.class, args); }

    @GetMapping("/payment/process")
    public String processPayment() {
        if (random.nextInt(10) < 6) throw new RuntimeException("Payment Service Down");
        return "✅ Payment Processed Successfully";
    }
}


//Order Service
package com.example.order;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;

@SpringBootApplication
@RestController
@RequestMapping("/order")
public class OrderApp {
    RestTemplate rest = new RestTemplate();
    public static void main(String[] args) { SpringApplication.run(OrderApp.class, args); }

    @GetMapping("/place")
    @CircuitBreaker(name = "paymentCB", fallbackMethod = "fallback")
    public String placeOrder() {
        String response = rest.getForObject("http://localhost:8082/payment/process", String.class);
        return "🛒 Order Confirmed → " + response;
    }

    public String fallback(Exception e) {
        return "⚡ Payment temporarily unavailable, order saved for retry.";
    }
}


//Retry Pattern
/*Scenario A StockService occasionally times out due to network glitches.
The ProductService retries automatically before giving up.*/
//Dependenices
<dependency>
  <groupId>org.springframework.retry</groupId>
  <artifactId>spring-retry</artifactId>
</dependency>

//StockService (port 8083)
package com.example.stock;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.web.bind.annotation.*;
import java.util.Random;

@SpringBootApplication
@RestController
public class StockApp {
    Random random = new Random();
    public static void main(String[] args){ SpringApplication.run(StockApp.class,args); }

    @GetMapping("/stock/check")
    String checkStock() {
        if (random.nextBoolean()) throw new RuntimeException("Stock Service Timeout");
        return "✅ Stock Available (42 units)";
    }
}


//ProductService (port 8084)
package com.example.product;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.retry.annotation.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

@SpringBootApplication
@EnableRetry
@RestController
@RequestMapping("/product")
public class ProductApp {
    RestTemplate rest = new RestTemplate();
    public static void main(String[] args){ SpringApplication.run(ProductApp.class,args); }

    @GetMapping("/fetch")
    @Retryable(value = RuntimeException.class, maxAttempts = 3, backoff = @Backoff(delay = 1000))
    String fetchProduct() {
        return "📦 Product Details → " + rest.getForObject("http://localhost:8083/stock/check", String.class);
    }

    @Recover
    String recover(RuntimeException e) {
        return "🚫 Stock service unreachable, please try later.";
    }
}




//Chained Service
//Booking involves three dependent services — Booking → Payment → Notification.

BookingService (port 8085)
package com.example.booking;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

@SpringBootApplication
@RestController
@RequestMapping("/booking")
public class BookingApp {
    RestTemplate rest = new RestTemplate();
    public static void main(String[] args){ SpringApplication.run(BookingApp.class,args); }

    @GetMapping("/create")
    String createBooking() {
        String payment = rest.getForObject("http://localhost:8086/payment/pay", String.class);
        String notify = rest.getForObject("http://localhost:8087/notify/send", String.class);
        return "🎟️ Booking Created → " + payment + " → " + notify;
    }
}


//PaymentService (port 8086)
package com.example.payment;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.web.bind.annotation.*;

@SpringBootApplication
@RestController
public class PaymentApp {
    public static void main(String[] args){ SpringApplication.run(PaymentApp.class,args); }

    @GetMapping("/payment/pay")
    String pay() { return "💳 Payment Successful"; }
}

//NotificationService (port 8087)
package com.example.notify;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.web.bind.annotation.*;

@SpringBootApplication
@RestController
public class NotificationApp {
    public static void main(String[] args){ SpringApplication.run(NotificationApp.class,args); }

    @GetMapping("/notify/send")
    String send(){ return "📩 Email Confirmation Sent"; }
}


//Aggregator Pattern
//A DashboardService fetches info from multiple microservices and merges results.

//Dependencies

//DashboardService (port 8088)
package com.example.dashboard;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;

@SpringBootApplication
@RestController
@RequestMapping("/dashboard")
public class DashboardApp {
    WebClient client = WebClient.create();
    public static void main(String[] args){ SpringApplication.run(DashboardApp.class,args); }

    @GetMapping("/summary")
    Mono<String> summary() {
        Mono<String> orders = client.get().uri("http://localhost:8081/order/place").retrieve().bodyToMono(String.class);
        Mono<String> stock = client.get().uri("http://localhost:8083/stock/check").retrieve().bodyToMono(String.class);

        return Mono.zip(orders, stock, (o, s) -> "📊 Dashboard → " + o + " | " + s);
    }
}


//Rate Limiting Pattern
//An API Gateway limits excessive user requests (using a simple in-memory limiter).
//Gateway (port 8089)
package com.example.gateway;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@SpringBootApplication
@RestController
public class GatewayApp {
    Map<String, Integer> requestCount = new HashMap<>();
    Map<String, Long> timestamp = new HashMap<>();
    static final int LIMIT = 3, WINDOW_MS = 5000;

    public static void main(String[] args){ SpringApplication.run(GatewayApp.class,args); }

    @GetMapping("/api/{user}")
    synchronized String handle(@PathVariable String user){
        long now = System.currentTimeMillis();
        timestamp.putIfAbsent(user, now);
        if (now - timestamp.get(user) > WINDOW_MS) {
            timestamp.put(user, now);
            requestCount.put(user, 0);
        }
        requestCount.put(user, requestCount.getOrDefault(user, 0) + 1);
        return (requestCount.get(user) > LIMIT) ?
            "🚫 Too Many Requests, please wait." : "✅ Request Served for " + user;
    }
}


//Combined Example (Retry + Circuit Breaker)
//An OrderService calls unstable PaymentService and uses both retry + circuit breaker.
//OrderService (port 8090)

package com.example.order;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.retry.annotation.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;

@SpringBootApplication
@EnableRetry
@RestController
@RequestMapping("/order")
public class OrderApp {
    RestTemplate rest = new RestTemplate();
    public static void main(String[] args){ SpringApplication.run(OrderApp.class,args); }

    @GetMapping("/process")
    @Retryable(value=RuntimeException.class, maxAttempts=3, backoff=@Backoff(delay=1000))
    @CircuitBreaker(name="paymentCB", fallbackMethod="fallback")
    String processOrder() {
        return "🛍️ Order → " + rest.getForObject("http://localhost:8082/payment/process", String.class);
    }

    String fallback(Exception e){ return "⚡ Payment service down, order queued."; }
}


