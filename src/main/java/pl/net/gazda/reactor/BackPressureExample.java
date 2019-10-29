package pl.net.gazda.reactor;

import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Subscription;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import static com.google.common.primitives.Ints.asList;
import static java.lang.Thread.sleep;

@Slf4j
public class BackPressureExample {
    public static void main(String[] args) throws InterruptedException {


        Flux.fromIterable(asList(1, 2, 3, 4, 5))
                .subscribeOn(Schedulers.elastic())
                .subscribe(new MyCustomBackPressureSubscriber<>())
        ;


        sleep(5000);
    }

    static class MyCustomBackPressureSubscriber<T> extends BaseSubscriber<T> {

        int processed = 0;
        final int limit = 2;

        @Override
        protected void hookOnSubscribe(Subscription subscription) {
            request(limit);
        }

        @Override
        protected void hookOnNext(T value) {
            log.info("processing {}", value);
            processed++;

            if (processed == limit) {
                //simulates doing some business stuff here where we need to slow down
                waitBeforeNextBatch();

                processed = 0;
                request(limit);
            }
        }

        private void waitBeforeNextBatch() {
            try {
                sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }
}
