object TestCustomStage extends App {
  implicit val system = ActorSystem("toto")
  implicit val mat = ActorMaterializer()
  import system.dispatcher

  val value = Source.queue[Int](100, OverflowStrategy.backpressure)
    .via(new CustomCustomStage(i => i % 10)).toMat(Sink.foreach(println))(Keep.both)
    .run()

  value._2.onComplete {
    case Success(e) => system.terminate()
    case Failure(e) => system.terminate()
  }

  value._1.offer(10)
  value._1.offer(10)
  value._1.offer(20)
  value._1.offer(24)
  value._1.offer(34)
  value._1.offer(30)
  value._1.complete()

  /*
  Vector(10, 10, 20)
  Vector(24, 34)
  Vector(30)
  */
}

class CustomCustomStage[T, U](lens: T => U) extends GraphStage[FlowShape[T, Seq[T]]]{
  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = {

    new GraphStageLogic(shape) {

      private var o: Option[U] = None
      private var collection: mutable.Builder[T, Vector[T]] = Vector.newBuilder[T]

      this.setHandlers(shape.in, shape.out, new InHandler with OutHandler {
        // onPush: new upstream element available. grab() to ... grab it.
        override def onPush(): Unit = {
          val item: T = grab(shape.in)
          val value: U = lens(item)

          if (o.isEmpty || o.contains(value)) {
            // we start a new collection or pursuit the existing one
            collection += item
            pull(shape.in) // give me more!
          } else {
            // we need to push the existing collection and start an empty one
            val finalCollection = collection.result()
            collection.clear()
            collection += item
            push(shape.out, finalCollection) // push to the next handler
            // we do not pull anything here, because we are pushing an item
            // anyway we wouldn't be able, that will crash! (that would do 2 pulls on the inlet)
          }

          o = Some(value)
        }

        // onPull: downstream is ready to get something.
        // we don't push anything here, we push when it's useful, in onPush method only!
        // so we just "transmit" the demand upstream
        override def onPull(): Unit = {
          pull(shape.in)
        }

        @scala.throws[Exception](classOf[Exception])
        override def onUpstreamFinish(): Unit = {
          // the last item can't possibly have been pushed, we need to do that here!
          val finalCollection = collection.result()
          if (finalCollection.nonEmpty) {
            // push(shape.out, finalCollection) // push to the next handler
            // we can't push! because it's probable push() was already summoned, and we can't ask twice
            // without a pull having taking place in-between (1-1)
            // "There can be only one outstanding push request at any given time."
            // Hopefully, there is another method to emit something: "emit"
            // That will push() if possible, otherwise wait for a onPull then push afterwards
            emit(shape.out, finalCollection)
          }
          // this method is not empty! it calls completeStage(), let's not forget it
          super.onUpstreamFinish()
        }

      })

      override def postStop(): Unit = {
        // cleanup our mess
        collection.clear()
      }
    }
  }

  val in = Inlet[T]("let me in")
  val out = Outlet[Seq[T]]("let me out")

  // IMPORTANT: extract the in and out outside of this method
  // it is called numerous times so it would give a new Inlet/Outlet references to each
  // and that will causes issues
  override def shape: FlowShape[T, Seq[T]] = {
    FlowShape(in, out)
  }
}


class CustomCustomStageTest extends FlatSpec with Matchers {
    "my test" should "work with the TestSink" in {
      implicit val system = ActorSystem("test")
      implicit val mat = ActorMaterializer()

      val value = Source.queue[Int](100, OverflowStrategy.backpressure)
        .via(new CustomCustomStage(i => i % 10)).toMat(TestSink.probe)(Keep.both)
        .run()

      List(10, 10, 20, 24, 34, 30).foreach(value._1.offer)
      value._1.complete()

      value._2.request(3) // this is the number of element the sink will pull
      // if we don't pull as many as we should, it will timeout and the test will fail
      value._2.expectNext(Vector(10, 10, 20))
      value._2.expectNext(Vector(24, 34), Vector(30))
      value._2.expectComplete()
    }
  
  "my test" should "work with Actors!" in {
      implicit val system = ActorSystem("test")
      implicit val mat = ActorMaterializer()

      val probe = TestProbe()
      val queue = Source.queue[Int](100, OverflowStrategy.backpressure)
        .via(new CustomCustomStage(i => i % 10)).to(Sink.actorRef(probe.ref, "done!"))
        .run()

      List(10, 10, 20, 24, 34, 30).foreach(queue.offer)
      queue.complete()

      probe.expectMsg(Vector(10, 10, 20))
      probe.expectMsg(Vector(24, 34))
      probe.expectMsg(Vector(30))
      probe.expectMsg("done!")
    }
}
