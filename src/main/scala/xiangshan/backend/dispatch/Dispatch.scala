package xiangshan.backend.dispatch

import chisel3._
import chisel3.util._
import xiangshan._
import utils._
import xiangshan.backend.regfile.RfReadPort

case class DispatchParameters
(
  DqEnqWidth: Int,
  IntDqSize: Int,
  FpDqSize: Int,
  LsDqSize: Int,
  IntDqDeqWidth: Int,
  FpDqDeqWidth: Int,
  LsDqDeqWidth: Int
)

class Dispatch() extends XSModule with NeedImpl {
  val io = IO(new Bundle() {
    // flush or replay
    val redirect = Flipped(ValidIO(new Redirect))
    // from rename
    val fromRename = Vec(RenameWidth, Flipped(DecoupledIO(new MicroOp)))
    // enq Roq
    val toRoq =  Vec(RenameWidth, DecoupledIO(new MicroOp))
    // get RoqIdx
    val roqIdxs = Input(Vec(RenameWidth, UInt(RoqIdxWidth.W)))
    // enq Moq
    val toMoq =  Vec(RenameWidth, DecoupledIO(new MicroOp))
    // get MoqIdx
    val moqIdxs = Input(Vec(RenameWidth, UInt(MoqIdxWidth.W)))

    // read regfile
    val readIntRf = Vec(NRIntReadPorts, Flipped(new RfReadPort))
    val readFpRf = Vec(NRFpReadPorts - exuParameters.StuCnt, Flipped(new RfReadPort))
    // read reg status (busy/ready)
    val intPregRdy = Vec(NRIntReadPorts, Input(Bool()))
    val fpPregRdy = Vec(NRFpReadPorts - exuParameters.StuCnt, Input(Bool()))
    // load + store reg status (busy/ready)
    val intMemRegAddr = Vec(NRMemReadPorts, Output(UInt(PhyRegIdxWidth.W)))
    val fpMemRegAddr = Vec(exuParameters.StuCnt, Output(UInt(PhyRegIdxWidth.W)))
    val intMemRegRdy = Vec(NRMemReadPorts, Input(Bool()))
    val fpMemRegRdy = Vec(exuParameters.StuCnt, Input(Bool()))

    // to reservation stations
    val numExist = Input(Vec(exuParameters.ExuCnt, UInt(log2Ceil(IssQueSize).W)))
    val enqIQCtrl = Vec(exuParameters.ExuCnt, DecoupledIO(new MicroOp))
    val enqIQData = Vec(exuParameters.ExuCnt - exuParameters.LsExuCnt, Output(new ExuInput))
  })

  val dispatch1 = Module(new Dispatch1)
  val intDq = Module(new DispatchQueue(dpParams.IntDqSize, dpParams.DqEnqWidth, dpParams.IntDqDeqWidth, "IntDpQ"))
  val fpDq = Module(new DispatchQueue(dpParams.FpDqSize, dpParams.DqEnqWidth, dpParams.FpDqDeqWidth, "FpDpQ"))
  val lsDq = Module(new DispatchQueue(dpParams.LsDqSize, dpParams.DqEnqWidth, dpParams.LsDqDeqWidth, "LsDpQ"))

  // pipeline between rename and dispatch
  // accepts all at once
  for (i <- 0 until RenameWidth) {
    PipelineConnect(io.fromRename(i), dispatch1.io.fromRename(i), dispatch1.io.recv(i), false.B)
  }

  // dispatch 1: accept uops from rename and dispatch them to the three dispatch queues
  dispatch1.io.redirect <> io.redirect
  dispatch1.io.toRoq <> io.toRoq
  dispatch1.io.roqIdxs <> io.roqIdxs
  dispatch1.io.toMoq <> io.toMoq
  dispatch1.io.moqIdxs <> io.moqIdxs
  dispatch1.io.toIntDq <> intDq.io.enq
  dispatch1.io.toFpDq <> fpDq.io.enq
  dispatch1.io.toLsDq <> lsDq.io.enq

  // dispatch queue: queue uops and dispatch them to different reservation stations or issue queues
  // it may cancel the uops
  intDq.io.redirect <> io.redirect
  fpDq.io.redirect <> io.redirect
  lsDq.io.redirect <> io.redirect

  // Int dispatch queue to Int reservation stations
  val intDispatch = Module(new Dispatch2Int)
  intDispatch.io.fromDq <> intDq.io.deq
  intDispatch.io.readRf <> io.readIntRf
  intDispatch.io.regRdy := io.intPregRdy
  intDispatch.io.numExist.zipWithIndex.map({case (num, i) => num := io.numExist(i) })
  intDispatch.io.enqIQCtrl.zipWithIndex.map({case (enq, i) => enq <> io.enqIQCtrl(i) })
  intDispatch.io.enqIQData.zipWithIndex.map({case (enq, i) => enq <> io.enqIQData(i) })

  // TODO: Fp dispatch queue to Fp reservation stations
  fpDq.io.deq <> DontCare
  io.readFpRf <> DontCare

  // Load/store dispatch queue to load/store issue queues
  val lsDispatch = Module(new Dispatch2Ls)
  lsDispatch.io.fromDq <> lsDq.io.deq
  lsDispatch.io.intRegAddr <> io.intMemRegAddr
  lsDispatch.io.fpRegAddr <> io.fpMemRegAddr
  lsDispatch.io.intRegRdy <> io.intMemRegRdy
  lsDispatch.io.fpRegRdy <> io.fpMemRegRdy
  lsDispatch.io.numExist.zipWithIndex.map({case (num, i) => num := io.numExist(exuParameters.IntExuCnt + exuParameters.FpExuCnt + i) })
  lsDispatch.io.enqIQCtrl.zipWithIndex.map({case (enq, i) => enq <> io.enqIQCtrl(exuParameters.IntExuCnt + exuParameters.FpExuCnt + i) })
}
