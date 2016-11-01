# IG Havoc

[![Clojars Project](https://img.shields.io/clojars/v/ig/havoc.svg)](https://clojars.org/ig/havoc)

Clojure library to do automated resilience testing using [Docker Compose](https://docs.docker.com/compose/overview/).

The library contains apis to:

* Control Docker containers
* Inject network faults between containers
* Simulate misbehaving http server
* test.check generators to build a random resilience testing scenarios

## Usage

```Clojure
(require '[ig.havoc.core :as havoc])
```

### Setup

First you need to create a [Docker Compose file](https://docs.docker.com/compose/compose-file/) for the system that you want
to test.

To inject network faults, `iptables` and `tc` (Linux Traffic Control) must be available in the containers and containers must
be run in privileged mode.

For example, here is a Docker Compose file that will start a Zookeeper and Kafka server:

     version: "2"
     services:
      zoo1:
        image: wurstmeister/zookeeper:3.4.6
        privileged: true
      kafka1:
        image: wurstmeister/kafka:0.9.0.1
        privileged: true
        environment:
           KAFKA_BROKER_ID: 1
           KAFKA_ADVERTISED_PORT: 9092
           KAFKA_ZOOKEEPER_CONNECT: zoo1:2181
           KAFKA_CREATE_TOPICS: "THE.TEST:20:2"

An example of a Dockerfile that install `iptables` and `tc` on a [Tomcat Container](https://hub.docker.com/_/tomcat/)

    FROM tomcat:8.5.4-jre8-alpine

    RUN apk upgrade --update && \
        apk add iproute2 iptables && \
        ln -s /usr/lib/tc/ /lib

Now you can define a couple of helper functions to bring the system up and down. Something like:

```Clojure
(require '[clojure.java.shell :as shell])

(defn start-system []
   (shell/sh "docker-compose"
             "-p" "your-project-name"
             "-f" "docker-compose.yml" "up"
             "-d"
             "--build"
             "--force-recreate"))

(defn stop-system []
   (shell/sh "docker-compose"
             "-p" "your-project-name"
             "-f" "docker-compose.yml" "down" "-v" "--remove-orphans"))
```

Note the `-p` option for the project name. We will use it latter.

See the [Docker Compose CLI](https://docs.docker.com/compose/reference/) for more details about the options.

Now we can start the system:

```Clojure
(start-system)
```

And control the containers, we create a new docker controller:

```Clojure
(def docker (havoc/create-docker "http://localhost:8765" "your-project-name"))
```

Note that the second parameter must be the project name that we used to start the system.

The first parameter is the url of the Docker Remote API. You can either configure your [Docker daemon](https://docs.docker.com/engine/reference/commandline/dockerd/) `-H` option.
If you are using Docker from Mac, see [a workaround](https://forums.docker.com/t/remote-api-with-docker-for-mac-beta/15639/6).

### Control containers

Now we can stop and start a container:

```Clojure
(havoc/exec! docker
             {:command :container/stop
              :host    :zoo1})

(havoc/exec! docker
             {:command :container/start
              :host    :zoo1})
```

Other commands are kill and restart.

### Injecting network faults

To inject faults in the network:

```Clojure
(havoc/exec! docker
             {:command :link/cut
              :from    :zoo1
              :to      :kafka1})

(havoc/exec! docker
             {:command :link/fix
              :from    :zoo1
              :to      :kafka1})

(havoc/exec! docker
             {:command :link/flaky
              :from    :zoo1
              :to      :kafka1
              :delay   {:time        100
                        :jitter      1000
                        :correlation 90}
              :loss    {:percent     30
                        :correlation 75}
              :corrupt {:percent 5}})
```

The parameters to the `:link/flaky` command have a direct relation from [Linux Traffic Control NetEm](http://man7.org/linux/man-pages/man8/tc-netem.8.html).
If one of the parameters is not specified, it will not be applied.
To revert it:

```Clojure
(havoc/exec! docker
             {:command :link/fast
              :from    :zoo1
              :to      :kafka1})
```

### Evil Http Server

For testing, you will need to mock/fake third party dependencies or other services within your company.

Havoc comes with a middleware that you can add to those fakes that can inject invalid/bogus responses.

Here is a simple fake server that includes the evil middleware:

```Clojure
(ns your.fake.server
  (:require
    [compojure.core :refer [defroutes ANY]]
    [ring.adapter.jetty :as jetty]
    [ig.havoc.evil-http-server-mw :as evil])
  (:use ring.middleware.params)
  (:gen-class))

(defroutes api
           (ANY "/" []
             {:status 200
              :body   "hi there!"}))

(defn -main [& args]
  (jetty/run-jetty
    (evil/create-ring-mw
      (wrap-params (var api)))
    {:port  80
     :join? true}))
```

You will need to add to the Docker compose file the fake server:

```
  fake:
     build: fake
     ports:
       - "3001:80"
```

Note that we are exposing port 3001.

The Dockerfile for that fake server can be as simple as:

```
FROM java:8u92-jre-alpine

COPY fake-uberjar.jar /fake-uberjar.jar

CMD ["java", "-Xmx1024m", "-server", "-jar", "/fake-uberjar.jar"]
```

Note that we expect the build system to generate a "fake-uberjar.jar". After you restart the system, the server should
be available:

```Console
$ wget -q -O -  http://localhost:3001/
hi there!
```

Now from our test we can control the behaviour of the evil middleware:

```Clojure
(havoc/exec! docker
             {:command   :http-server/set!
              :host-port [:fake 3001]
              :state     {:faults         #{:random-header
                                            :random-content-length
                                            :random-http-status}
                          :error-code     429
                          :content-length -12323}
              })
```

If we query again the server:

```Console
$ wget -S -q -O - http://localhost:3001/
HTTP/1.1 429 429
Date: Sun, 23 Oct 2016 00:04:40 GMT
EA\\?\027=\021????\027??X*?;A\177T\f?s: G    DE   : ;   5\0200~   _  b\037   8 D\035\b 5 :  .
Content-Length: -12323
Server: Jetty(9.2.z-SNAPSHOT)
```

To remove faults just:
```Clojure
(havoc/exec! docker
             {:command   :http-server/set!
              :host-port [:fake 3001]})
```

Other faults are :empty-response, :random-response and :infinite-response.

## Property based testing

What we have seen so far will allow us to write the usual resilience test, were we will test things
like "The system should work if just one instance dies" or "X should reconnect to Y when Y is restarted".

But even if these tests are a good starting point, most of the outages happen because some unexpected set of events
happen. And of course if there weren't expected, there is no way that we will write a test for them.

Havoc comes with a test.check generator that can be used to generate random commands for the system under test.

First we need to define what is the initial state of the system:

```Clojure
(def initial-state {:kafka1         {:status :ok}
                    :kafka2         {:status :ok}
                    :zoo1           {:status :ok}
                    [:kafka1 :zoo1] {:status :ok}
                    [:kafka2 :zoo1] {:status :ok}
                    })
```
Keywords correspond to containers, vectors correspond to the network between the containers.

Note that we do not specify the initial state of the fake container as we don't want to mess with it.

Second, we need to specify what are the other healthy states on the system. Havoc will make sure that the generated
plan always leaves the system in a "good enough" state.

For example, if there were two Kafkas in the system, we could specify that we just really need one for the system
to work:

```Clojure
(def final-states #{initial-state
                    (assoc-in initial-state [:kafka1 :status] :stopped)
                    (assoc-in initial-state [:kafka2 :status] :stopped)})
```

Last, we need to specify list of faults that we want to inject.

```Clojure
(def command-generators [(havoc/container-gen :kafka1)
                         (havoc/container-gen :kafka2)

                         (havoc/link-gen [:kafka1 :zoo1])
                         (havoc/link-gen [:kafka2 :zoo1])
                         (havoc/link-handicaps-gen [:kafka1 :zoo1])
                         (havoc/link-handicaps-gen [:kafka2 :zoo1])

                         (havoc/evil-http-server-gen [:fake 3001])])
```

And now we can generate a random plan, with 2 to 4 commands:

```Clojure
(require '[clojure.test.check.generators :as gen])
(def a-random-plan (first
                     (gen/sample
                       (havoc/random-plan-generator [2 4]
                                                    initial-state
                                                    final-states
                                                    command-generators))))
```

The plan has 3 set of commands:

```Clojure
(doseq [cmd (havoc/initial->broken a-random-plan)]
   (println cmd)
   (havoc/exec! docker cmd)
   (Thread/sleep 10000))

(doseq [cmd (havoc/broken->final a-random-plan)]
   (havoc/exec! docker cmd))

;; In case you want to go back to the initial state instead of restarting the whole system
(doseq [cmd (havoc/final->initial a-random-plan)]
   (havoc/exec! docker cmd))
```

To test that all the healthy states are indeed healthy, we can generate a list of commands to move from
one state to another:

```Clojure
(map (partial havoc/commands-from-to command-generators initial-state) final-states)
```

### Fault customization

Sometimes you know that your system cannot handle some type of fault.

For example, your HTTP client may not be able to handle incorrect content length and you just want to generate 400 and 500 error codes:

```Clojure
(havoc/evil-http-server-gen [:fake 3001]
                            :faults (dissoc havoc/evil-http-server-faults :infinite-response)
                            :error-codes #{400 500})
```

To customize the faults in the network:

```Clojure
(havoc/link-handicaps-gen [:kafka2 :zoo1] :handicaps (dissoc havoc/link-handicaps :link/corrupt))
```

### Global constraints

You can also add global constraints to the plan generator.

Right now Havoc comes with one:

```
(havoc/random-plan-generator [2 4]
                             initial-state
                             final-states
                             command-generators
                             :global-constraint (havoc/keep-some-containers-ok 1 [:kafka1 :kafka2]))
```

This will generate a plan in which one of the Kafkas will always be up and running (and never restarted)


## License

Copyright © 2016 IG Group

Distributed under Apache Licence 2.0.
