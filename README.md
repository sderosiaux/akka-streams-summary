# Akka Streams: A stream processing library

As any streaming library, we have the concept of
- Source (input): create messages
- Channel (flow/stage) (more globally, Shape here): messages pass through
- Sink (output): output messages somewhere else (Note: it exists a Sink.ignore to output to a black-hole)

In akka-streams, we have also these abstractions:

- Outlet[T] related to a Source[T]: .out on the Source
- Inlet[T] related to a Sink[T]: .in on the Sink

## Shapes

A Shape is a "box" with inputs and outputs, something that "processes" messages. There are some specific kind of shapes:

- `Shape`: the top abstract class for any shape. Contains a empty list of inputs (inlets, which are the input "ports"), and outputs (outlets, which are the output "ports")
- `SourceShape`: 0 --> 1 (a Source has a SourceShape, and it's the start of a Graph)
- `SinkShape`: 1 --> 0 output only (a Sink has a SinkShape, and it's the end of a Graph)
- `FlowShape`: 1 --> 1
- `BidiShape`: 2 <-> 2 (1 --> 1 with 1 <-- 1, bidirectional)
- `FanOutShape`: 1 --> N (typically a Broadcast)
- `FanInShape`: N --> 1 (typically a Merge)
- `ClosedShape`: Shape with closed inputs and closed outputs that can be materialized (executed). It's just the combinaison of other shapes. Typically, it's a RunnableGraph.

Combining shapes give another shape. ie: a SourceShape + a FlowShape gives a new SourceShape.

## Graph

A `Graph` is the tiniest Unit in Akka Streams. Everything is a graph. Every graph has a shape (which contains in/out ports).
A `Graph` can combines inputs (sources), flows, or outputs (sinks).
A `Graph` can be partial (still exposing opened inputs or outputs) or closed (self-sufficient graph, no input, no output).

- `ActorMaterializer`: Akka Streams specific. Provisions actors to execute a pipeline (a Graph)
- `ActorMaterializerSetting`s: ActorMaterializer ... settings. Can have a custom supervision strategy (if exception, Resume, Restart, or Stop), can add logging, can configure thresholds..
- `RunnableGraph`: A graph is a whole set of Outlet/FlowShape/Inlet linked together, that is closed.
 - it starts with a Source (using its Outlet), ends with a Sink (using its Inlet)
- It can be stopped anytime using a `KillSwitch`.

## Closed Graphs

```scala
// this is a Graph that can be run
Source.fromFuture(Future { 2 })
    .via(Flow[Int].map(_ * 2))
    .to(Sink.foreach(println))
    
// this is the same with the full DSL
val g: RunnableGraph[_] = RunnableGraph.fromGraph(GraphDSL.create() {
    implicit builder =>
      val source = builder.add(Source.fromFuture(Future { 2 }))
      val multiply = builder.add(Flow[Int].map(_ * 2))
      val sink = builder.add(Sink.foreach(println))

      import GraphDSL.Implicits._
      source ~> multiply ~> sink
      ClosedShape
  })
```

We are using the GraphDSL of Akka Streams to define the graph. Sometimes, it's overkilled, when you have a linear flow of data where you could just use the standard `.map` `.filter` and so on.

When the graph is not linear (broadcasting, merging..) then the GraphDSL is nice to use.

## Partial Graphs

Here, a Graph that is not closed, not runnable. It just provides an abstraction and create a simple `Graph` containing a `Flow` shape, that simply acts as a diamond internally:
```scala
val diamond: Graph[FlowShape[Int, Int], NotUsed] = GraphDSL.create() { implicit builder =>
  val split = builder.add(Balance[Int](2))
  val merge = builder.add(Merge[Int](2))
  split ~> merge
  split ~> merge
  FlowShape(split.in, merge.out)
}
```

> Notice the 2 links between split and merge (each has 2 ports). Akka Streams is smart, and will crash if you forget to link your stuff : `java.lang.IllegalArgumentException: requirement failed: The inlets [Balance.in] and outlets [Merge.out] must correspond to the inlets [Balance.in, Merge.in1] and outlets [Balance.out1, Merge.out]`

To make it useful, we need to create a `Flow` from it, then we can use it as any flow:
```scala
val diamondGraph: Flow[Int, Int, NotUsed] = Flow.fromGraph(diamond)
Source.single(5).via(diamondGraph).runForeach(println)
// Outputs "5"
```

A Graph can contains (ie: the builder can return..) any type of Shape we already talk about: `SourceShape`, `SinkShape` etc. It's just an abstraction using multiple Shapes internally.

For instance, a Source that just expose random numbers (it's useless in this case, but just for demo purpose):
```scala
val s = GraphDSL.create() { implicit builder =>
   val flow = builder.add(Flow[Double].map(identity)) // look the note below
   val ss = Source.fromIterator(() => Iterator.continually(math.random))
   ss ~> flow
   SourceShape(flow.out)
}
Source.fromGraph(s).runForeach(println)
```
> Note that I create a dummy flow because I couldn't return the original source shape: `UnsupportedOperationException: cannot replace the shape of the EmptyModule` (it's not supported because it's stupid! `Source.fromIterator` is already doing the job)

### Waiting for a RunnableGraph to end

You can get a Future from a Graph only using specific syntax:

```scala
// we can subscribe to the future: .onComplete(...)
val foo: Future[Done] = Source.single("Hello").runWith(Sink.foreach(println)))
```
With this syntax, you wouldn't get a Future:
```scala
val foo: NotUsed = Source.single("Hello").runWith(Sink.foreach(println))).to(Sink.foreach(println)).run()
```

## Flow 

A flow can be 
- 1 -> 1
- 1 -> N: faning out events (Broadcast) or acts as a load balancer (Balance).
- N -> 1: merge 1 event of several inputs into 1 event in output; or simply Concat (3 inputs = 3 outputs).

A Flow is a Graph.

```scala
// this is a Flow: Int -> Int
Flow[Int].map(_ * 2)
```

## Source

There are a lot of way (syntaxes) to execute a pipeline in Akka Streams.
For instance, a Source can be ran without Graph, without Flow (it's created underneath):
```scala
Source(0 to 5).map(100 * _).runWith(Sink.fold(0)(_ + _)) // returns a Future[Int]
FileIO.fromPath(Paths.get("log.txt")).runFold(0)(_ + _.length).foreach(println)
```

## Composition

We can compose anything using `.via`, like a (source+flow) => 1x source, 2x flows => 1x flow

## Using Actors

It's possible to bind the Source to a custom Actor.
akka-streams will send requests asking for `n` elements. The actor can then call `n` times `onNext` (not more or an exception will be thrown):
```scala
class Toto extends Actor with ActorPublisher[Char] {
  override def receive = {
    case x @ Request(n) => println(x); (0 until n.toInt).foreach(_ => onNext(Random.nextPrintableChar()))
    case Cancel => context.stop(self)
  }
}

Source.actorPublisher(Props(classOf[Toto])).runForeach(println)
```

## Threading

A stream does not run on the caller thread. It uses a thread pool provided by a Materializer.
 
Several stages shares the same thread, except if you explicitely specified async boundaries to make them run concurrently (but still properly ordered to the sink):
```
.via(flow).async // asynchronous boundary
```

## Logging

We always want to know what's in the pipes. Instead of adding `println` everywhere, Akka Streams has a pluggable facility:

```
.via(processingStage).log("my flow")
```
It's using the DEBUG level, so make sure to adapt your configuration:
```
akka {
  loglevel = "DEBUG"
}
```

That will output something like:
```
[DEBUG] [11/09/2016 15:40:17.027] [default-akka.actor.default-dispatcher-7] [akka.stream.Log(akka://default/user/StreamSupervisor-0)] [my flow] Element: Hello
```

You can also get the blueprint of any Shape with `toString`. Better give a name to your components.
```scala
println(Source.single(0).map(_ * 2).named("my source"))
/*
Source(SourceShape(Map.out), CompositeModule [3232a28a]
  Name: nestedSource
  Modules:
    (singleSource) GraphStage(SingleSource(0)) [4229bb3f]
    (unnamed) [56cdfb3b] copy of GraphStage(Map(<function1>)) [2b91004a]
  Downstreams: 
    single.out -> Map.in
  Upstreams: 
    Map.in -> single.out
  MatValue: Atomic(singleSource[4229bb3f]))
*/
```
We can see everything: the flows, the upstreams/downstreams and the materialized value.

## Kafka

[reactive-kafka](https://github.com/akka/reactive-kafka) === akka-stream-kafka

## Alpakka

Set of connectors useable with Akka Streams:

- HTTP
- TCP
- File IO

# Reactive streams: A specification

- Subscriber[A] (Consumer), Publisher[B] (Producer), Subscription, Processor[A, B]
- asynchronous boundary: decouple components
- define backpressure model: signal to the source to handle
- push AND pull: fast consumer AND slow consumer (dynamic)
- ability to batch process

Some implementations are:
- RxJava
- Akka-streams
- Reactor
- Vert.x
- Slick



# Reactive Programming

- Responsive -> Resilient + Scalable -> Message Driven

# References

- https://medium.com/@kvnwbbr/diving-into-akka-streams-2770b3aeabb0 
- https://medium.com/reactive-programming/what-is-reactive-programming-bc9fa7f4a7fc
- https://medium.com/@kvnwbbr/a-journey-into-reactive-streams-5ee2a9cd7e29

