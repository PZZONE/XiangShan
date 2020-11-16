package cache.TLCTest

import chipsalliance.rocketchip.config.{Field, Parameters}
import chisel3._
import chiseltest.experimental.TestOptionBuilder._
import chiseltest.internal.VerilatorBackendAnnotation
import chiseltest._
import chiseltest.ChiselScalatestTester
import freechips.rocketchip.diplomacy.{LazyModule, LazyModuleImp}
import freechips.rocketchip.tilelink.{TLBuffer, TLDelayer, TLXbar}
import org.scalatest.{FlatSpec, Matchers}
import sifive.blocks.inclusivecache.{CacheParameters, InclusiveCache, InclusiveCacheMicroParameters}
import utils.{DebugIdentityNode, XSDebug}
import xiangshan.HasXSLog
import xiangshan.testutils.AddSinks

import scala.collection.mutable
import scala.collection.mutable.{ArrayBuffer, ListBuffer, Map, Queue, Seq}
import scala.util.Random

case class TLCCacheTestParams
(
  ways: Int = 4,
  banks: Int = 1,
  capacityKB: Int = 4,
  blockBytes: Int = 64,
  beatBytes: Int = 32
) {
  require(blockBytes >= beatBytes)
}

case object TLCCacheTestKey extends Field[TLCCacheTestParams]

class TLCCacheTestTopIO extends Bundle {
  val mastersIO = Vec(2, new TLCTestMasterMMIO())
  val slaveIO = new TLCTestSlaveMMIO()
}

class TLCCacheTestTop()(implicit p: Parameters) extends LazyModule {

  val masters = Array.fill(2)(LazyModule(new TLCMasterMMIO()))

  val l2params = p(TLCCacheTestKey)

  val l2 = LazyModule(new InclusiveCache(
    CacheParameters(
      level = 2,
      ways = l2params.ways,
      sets = l2params.capacityKB * 1024 / (l2params.blockBytes * l2params.ways * l2params.banks),
      blockBytes = l2params.blockBytes,
      beatBytes = l2params.beatBytes
    ),
    InclusiveCacheMicroParameters(
      writeBytes = l2params.beatBytes
    )
  ))

  val xbar = TLXbar()

  for (master <- masters) {
    xbar := TLDelayer(0.2) := DebugIdentityNode() := master.node
  }
  l2.node := TLBuffer() := DebugIdentityNode() := xbar

  val slave = LazyModule(new TLCSlaveMMIO())
  slave.node := TLDelayer(0.2) := DebugIdentityNode() := l2.node

  lazy val module = new LazyModuleImp(this) with HasXSLog {

    val io = IO(new TLCCacheTestTopIO)

    slave.module.io <> io.slaveIO
    masters zip io.mastersIO map { case (m, i) =>
      m.module.io <> i
    }
  }
}

class TLCCacheTestTopWrapper()(implicit p: Parameters) extends LazyModule {

  val testTop = LazyModule(new TLCCacheTestTop())

  lazy val module = new LazyModuleImp(this) {
    val io = IO(new TLCCacheTestTopIO)
    AddSinks()
    io <> testTop.module.io
  }
}

class TLCCacheTest extends FlatSpec with ChiselScalatestTester with Matchers with TLCOp {
  val slave_safe = 0
  val slave_granting = 1
  val slave_probing = 2

  def getRandomElement[A](l: List[A], random: Random): A =
    l(random.nextInt(l.length))

  top.Parameters.set(top.Parameters.debugParameters)

  it should "run" in {

    implicit val p = Parameters((site, up, here) => {
      case TLCCacheTestKey =>
        TLCCacheTestParams()
    })

    val addr_pool = {
      for (_ <- 0 to 128) yield BigInt(Random.nextInt(0xfffc) << 6)
    }.distinct.toList // align to block size
    val addr_list_len = addr_pool.length

    def peekBigInt(source: Data): BigInt = {
      source.peek().litValue()
    }

    def peekBoolean(source: Bool): Boolean = {
      source.peek().litToBoolean
    }

    test(LazyModule(new TLCCacheTestTopWrapper()).module)
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { c =>
        c.io.mastersIO.foreach { mio =>
          mio.AChannel.initSource().setSourceClock(c.clock)
          mio.CChannel.initSource().setSourceClock(c.clock)
          mio.EChannel.initSource().setSourceClock(c.clock)
          mio.BChannel.initSink().setSinkClock(c.clock)
          mio.DChannel.initSink().setSinkClock(c.clock)
        }
        c.io.slaveIO.AChannel.initSink().setSinkClock(c.clock)
        c.io.slaveIO.CChannel.initSink().setSinkClock(c.clock)
        c.io.slaveIO.EChannel.initSink().setSinkClock(c.clock)
        c.io.slaveIO.BChannel.initSource().setSourceClock(c.clock)
        c.io.slaveIO.DChannel.initSource().setSourceClock(c.clock)

        val mastersIO = c.io.mastersIO
        val slaveIO = c.io.slaveIO

        val serialList = ArrayBuffer[(Int, TLCTrans)]()
        val masterStateList = List.fill(2)(mutable.Map[BigInt, AddrState]())
        val masterAgentList = List.tabulate(2)(i => new TLCMasterAgent(i, 8, masterStateList(i), serialList))
        val slaveState = mutable.Map() ++ {
          addr_pool zip List.fill(addr_list_len)(new AddrState())
        }.toMap
        val slaveAgent = new TLCSlaveAgent(2, 8, slaveState, serialList)

        fork {
          val mio = mastersIO(0)
          val masterAgent = masterAgentList(0)
          while (true) {
            var AChannel_valid = false
            var CChannel_valid = false
            var EChannel_valid = false
            val BChannel_ready = true
            val DChannel_ready = true

            //E channel
            val EChannel_ready = peekBoolean(mio.EChannel.ready)
            val tmpE = masterAgent.peekE()
            if (tmpE.isDefined){
              EChannel_valid = true
              if (EChannel_valid && EChannel_ready) {
                mio.EChannel.bits.sink.poke(tmpE.get.sink.U)
                masterAgent.fireE()
              }
            }
            mio.EChannel.valid.poke(EChannel_valid.B)
            //D channel
            mio.DChannel.ready.poke(DChannel_ready.B)
            val DChannel_valid = peekBoolean(mio.DChannel.valid)
            if (DChannel_valid && DChannel_ready){ //fire
              val dCh = new TLCScalaD()
              dCh.opcode = peekBigInt(mio.DChannel.bits.opcode)
              dCh.param = peekBigInt(mio.DChannel.bits.param)
              dCh.size = peekBigInt(mio.DChannel.bits.size)
              dCh.source = peekBigInt(mio.DChannel.bits.source)
              dCh.sink = peekBigInt(mio.DChannel.bits.sink)
              dCh.denied = peekBoolean(mio.DChannel.bits.denied)
              dCh.data = peekBigInt(mio.DChannel.bits.data)
              masterAgent.fireD(dCh)
            }
            //C channel
            val CChannel_ready = peekBoolean(mio.CChannel.ready)
            masterAgent.issueC()
            val tmpC = masterAgent.peekC()
            if (tmpC.isDefined){
              CChannel_valid = true
              if (CChannel_valid && CChannel_ready) {
                mio.CChannel.bits.opcode.poke(tmpC.get.opcode.U)
                mio.CChannel.bits.param.poke(tmpC.get.param.U)
                mio.CChannel.bits.size.poke(tmpC.get.size.U)
                mio.CChannel.bits.source.poke(tmpC.get.source.U)
                mio.CChannel.bits.address.poke(tmpC.get.address.U)
                mio.CChannel.bits.data.poke(tmpC.get.data.U)
                masterAgent.fireC()
              }
            }
            mio.CChannel.valid.poke(CChannel_valid.B)
            //B channel
            mio.BChannel.ready.poke(BChannel_ready.B)
            val BChannel_valid = peekBoolean(mio.BChannel.valid)
            if (BChannel_valid && BChannel_ready){ //fire
              val bCh = new TLCScalaB()
              bCh.opcode = peekBigInt(mio.BChannel.bits.opcode)
              bCh.param = peekBigInt(mio.BChannel.bits.param)
              bCh.size = peekBigInt(mio.BChannel.bits.size)
              bCh.source = peekBigInt(mio.BChannel.bits.source)
              bCh.address = peekBigInt(mio.BChannel.bits.address)
              bCh.mask = peekBigInt(mio.BChannel.bits.mask)
              bCh.data = peekBigInt(mio.BChannel.bits.data)
              masterAgent.fireB(bCh)
            }
            masterAgent.tickB()
            //A channel
            val AChannel_ready = peekBoolean(mio.AChannel.ready)
            masterAgent.issueA()
            val tmpA = masterAgent.peekA()
            if (tmpA.isDefined){
              AChannel_valid = true
              if (AChannel_valid && AChannel_ready) {
                mio.AChannel.bits.opcode.poke(tmpA.get.opcode.U)
                mio.AChannel.bits.param.poke(tmpA.get.param.U)
                mio.AChannel.bits.size.poke(tmpA.get.size.U)
                mio.AChannel.bits.source.poke(tmpA.get.source.U)
                mio.AChannel.bits.address.poke(tmpA.get.address.U)
                mio.AChannel.bits.data.poke(tmpA.get.data.U)
                masterAgent.fireA()
              }
            }
            mio.AChannel.valid.poke(AChannel_valid.B)

            c.clock.step()
          }

        }.fork {
          val mio = mastersIO(1)
          val masterAgent = masterAgentList(1)
          while (true) {
            var AChannel_valid = false
            var CChannel_valid = false
            var EChannel_valid = false
            val BChannel_ready = true
            val DChannel_ready = true

            //E channel
            val EChannel_ready = peekBoolean(mio.EChannel.ready)
            val tmpE = masterAgent.peekE()
            if (tmpE.isDefined){
              EChannel_valid = true
              if (EChannel_valid && EChannel_ready) {
                mio.EChannel.bits.sink.poke(tmpE.get.sink.U)
                masterAgent.fireE()
              }
            }
            mio.EChannel.valid.poke(EChannel_valid.B)
            //D channel
            mio.DChannel.ready.poke(DChannel_ready.B)
            val DChannel_valid = peekBoolean(mio.DChannel.valid)
            if (DChannel_valid && DChannel_ready){ //fire
              val dCh = new TLCScalaD()
              dCh.opcode = peekBigInt(mio.DChannel.bits.opcode)
              dCh.param = peekBigInt(mio.DChannel.bits.param)
              dCh.size = peekBigInt(mio.DChannel.bits.size)
              dCh.source = peekBigInt(mio.DChannel.bits.source)
              dCh.sink = peekBigInt(mio.DChannel.bits.sink)
              dCh.denied = peekBoolean(mio.DChannel.bits.denied)
              dCh.data = peekBigInt(mio.DChannel.bits.data)
              masterAgent.fireD(dCh)
            }
            //C channel
            val CChannel_ready = peekBoolean(mio.CChannel.ready)
            masterAgent.issueC()
            val tmpC = masterAgent.peekC()
            if (tmpC.isDefined){
              CChannel_valid = true
              if (CChannel_valid && CChannel_ready) {
                mio.CChannel.bits.opcode.poke(tmpC.get.opcode.U)
                mio.CChannel.bits.param.poke(tmpC.get.param.U)
                mio.CChannel.bits.size.poke(tmpC.get.size.U)
                mio.CChannel.bits.source.poke(tmpC.get.source.U)
                mio.CChannel.bits.address.poke(tmpC.get.address.U)
                mio.CChannel.bits.data.poke(tmpC.get.data.U)
                masterAgent.fireC()
              }
            }
            mio.CChannel.valid.poke(CChannel_valid.B)
            //B channel
            mio.BChannel.ready.poke(BChannel_ready.B)
            val BChannel_valid = peekBoolean(mio.BChannel.valid)
            if (BChannel_valid && BChannel_ready){ //fire
              val bCh = new TLCScalaB()
              bCh.opcode = peekBigInt(mio.BChannel.bits.opcode)
              bCh.param = peekBigInt(mio.BChannel.bits.param)
              bCh.size = peekBigInt(mio.BChannel.bits.size)
              bCh.source = peekBigInt(mio.BChannel.bits.source)
              bCh.address = peekBigInt(mio.BChannel.bits.address)
              bCh.mask = peekBigInt(mio.BChannel.bits.mask)
              bCh.data = peekBigInt(mio.BChannel.bits.data)
              masterAgent.fireB(bCh)
            }
            masterAgent.tickB()
            //A channel
            val AChannel_ready = peekBoolean(mio.AChannel.ready)
            masterAgent.issueA()
            val tmpA = masterAgent.peekA()
            if (tmpA.isDefined){
              AChannel_valid = true
              if (AChannel_valid && AChannel_ready) {
                mio.AChannel.bits.opcode.poke(tmpA.get.opcode.U)
                mio.AChannel.bits.param.poke(tmpA.get.param.U)
                mio.AChannel.bits.size.poke(tmpA.get.size.U)
                mio.AChannel.bits.source.poke(tmpA.get.source.U)
                mio.AChannel.bits.address.poke(tmpA.get.address.U)
                mio.AChannel.bits.data.poke(tmpA.get.data.U)
                masterAgent.fireA()
              }
            }
            mio.AChannel.valid.poke(AChannel_valid.B)

            c.clock.step()
          }
        }.fork {
          val sio = slaveIO
          while (true) {

            val AChannel_ready = true
            val CChannel_ready = true
            val EChannel_ready = true
            var BChannel_valid = false
            var DChannel_valid = false

            //E channel
            sio.EChannel.ready.poke(EChannel_ready.B)
            val EChannel_valid = peekBoolean(sio.EChannel.valid)
            if (EChannel_valid && EChannel_ready) {
              val eCh = new TLCScalaE()
              eCh.sink = peekBigInt(sio.EChannel.bits.sink)
              slaveAgent.fireE(eCh)
            }
            //D channel
            val DChannel_ready = peekBoolean(sio.DChannel.ready)
            slaveAgent.issueD()
            val tmpD = slaveAgent.peekD()
            if (tmpD.isDefined) {
              DChannel_valid = true
              if (DChannel_valid && DChannel_ready) { //fire
                sio.DChannel.bits.opcode.poke(tmpD.get.opcode.U)
                sio.DChannel.bits.param.poke(tmpD.get.param.U)
                sio.DChannel.bits.size.poke(tmpD.get.size.U)
                sio.DChannel.bits.source.poke(tmpD.get.source.U)
                sio.DChannel.bits.sink.poke(tmpD.get.sink.U)
                sio.DChannel.bits.denied.poke(tmpD.get.denied.B)
                sio.DChannel.bits.data.poke(tmpD.get.data.U)
                slaveAgent.fireD()
              }
            }
            sio.DChannel.valid.poke(DChannel_valid.B)
            //C channel
            sio.CChannel.ready.poke(CChannel_ready.B)
            val CChannel_valid = peekBoolean(sio.CChannel.valid)
            if (CChannel_valid && CChannel_ready) { //fire
              val cCh = new TLCScalaC()
              cCh.opcode = peekBigInt(sio.CChannel.bits.opcode)
              cCh.param = peekBigInt(sio.CChannel.bits.param)
              cCh.size = peekBigInt(sio.CChannel.bits.size)
              cCh.source = peekBigInt(sio.CChannel.bits.source)
              cCh.address = peekBigInt(sio.CChannel.bits.address)
              cCh.data = peekBigInt(sio.CChannel.bits.data)
              slaveAgent.fireC(cCh)
            }
            slaveAgent.tickC()
            //B channel
            val BChannel_ready = peekBoolean(sio.BChannel.ready)
            slaveAgent.issueB()
            val tmpB = slaveAgent.peekB()
            if (tmpB.isDefined) {
              BChannel_valid = true
              if (BChannel_valid && BChannel_ready){
                sio.BChannel.bits.opcode.poke(tmpB.get.opcode.U)
                sio.BChannel.bits.param.poke(tmpB.get.param.U)
                sio.BChannel.bits.size.poke(tmpB.get.size.U)
                sio.BChannel.bits.source.poke(tmpB.get.source.U)
                sio.BChannel.bits.address.poke(tmpB.get.address.U)
                sio.BChannel.bits.mask.poke(tmpB.get.mask.U)
                sio.BChannel.bits.data.poke(tmpB.get.data.U)
                slaveAgent.fireB()
              }
            }
            sio.BChannel.valid.poke(BChannel_valid.B)
            //A channel
            sio.AChannel.ready.poke(AChannel_ready.B)
            val AChannel_valid = peekBoolean(sio.AChannel.valid)
            if (AChannel_valid && AChannel_ready) { //fire
              val aCh = new TLCScalaA()
              aCh.opcode = peekBigInt(sio.AChannel.bits.opcode)
              aCh.param = peekBigInt(sio.AChannel.bits.param)
              aCh.size = peekBigInt(sio.AChannel.bits.size)
              aCh.source = peekBigInt(sio.AChannel.bits.source)
              aCh.address = peekBigInt(sio.AChannel.bits.address)
              aCh.mask = peekBigInt(sio.AChannel.bits.mask)
              slaveAgent.fireA(aCh)
            }
            slaveAgent.tickA()

            c.clock.step()
          }
        }.join

        c.clock.setTimeout(1000)
      }
  }

}