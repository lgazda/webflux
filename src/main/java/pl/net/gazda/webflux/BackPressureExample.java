package pl.net.gazda.webflux;

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
                .subscribe(new MyCustomBackpressureSubscriber<>())
        ;


        sleep(5000);
    }

    static class MyCustomBackpressureSubscriber<T> extends BaseSubscriber<T> {

        int consumed = 0;
        final int limit = 2;

        @Override
        protected void hookOnSubscribe(Subscription subscription) {
            request(limit);
        }

        @Override
        protected void hookOnNext(T value) {
            consumed++;
            log.info("onNext {}", value);

            if (consumed == limit) {
                waitBeforeNextBatch();

                consumed = 0;
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
