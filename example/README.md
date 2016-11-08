# A full example

Contains a full example of the resilience testing of an application.

The application consumes messages from a cluster of Kafka's and pushes it to a third party. 
The application is not resilient enough and it is expected to fail.

## Usage

Docker should be installed and the Docker Remote API available at port 2376.

To run:

     lein go

## Files and folders

- test/example.clj: the test
- docker-compose.xml: setups the environment
- our-service: The service that we want to test
- fake: A fake of the third party service
