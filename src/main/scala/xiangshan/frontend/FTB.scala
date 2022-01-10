/***************************************************************************************
* Copyright (c) 2020-2021 Institute of Computing Technology, Chinese Academy of Sciences
* Copyright (c) 2020-2021 Peng Cheng Laboratory
*
* XiangShan is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*          http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
* EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
* MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

package xiangshan.frontend

import chipsalliance.rocketchip.config.Parameters
import chisel3._
import chisel3.stage.{ChiselGeneratorAnnotation, ChiselStage}
import chisel3.util._
import xiangshan._
import utils._
import chisel3.experimental.chiselName

import scala.math.min
import os.copy


trait FTBParams extends HasXSParameter with HasBPUConst {
  val numEntries = FtbSize
  val numWays    = FtbWays
  val numSets    = numEntries/numWays // 512
  val tagSize    = 20

  

  val TAR_STAT_SZ = 2
  def TAR_FIT = 0.U(TAR_STAT_SZ.W)
  def TAR_OVF = 1.U(TAR_STAT_SZ.W)
  def TAR_UDF = 2.U(TAR_STAT_SZ.W)

  def BR_OFFSET_LEN = 12
  def JMP_OFFSET_LEN = 20
}

class FtbSlot(val offsetLen: Int, val subOffsetLen: Option[Int] = None)(implicit p: Parameters) extends XSBundle with FTBParams {
  if (subOffsetLen.isDefined) {
    require(subOffsetLen.get <= offsetLen)
  }
  val offset  = UInt(log2Ceil(PredictWidth).W)
  val lower   = UInt(offsetLen.W)
  val tarStat = UInt(TAR_STAT_SZ.W)
  val sharing = Bool()
  val valid   = Bool()

  def setLowerStatByTarget(pc: UInt, target: UInt, isShare: Boolean) = {
    def getTargetStatByHigher(pc_higher: UInt, target_higher: UInt) =
      Mux(target_higher > pc_higher, TAR_OVF,
        Mux(target_higher < pc_higher, TAR_UDF, TAR_FIT))
    def getLowerByTarget(target: UInt, offsetLen: Int) = target(offsetLen, 1)
    val offLen = if (isShare) this.subOffsetLen.get else this.offsetLen
    val pc_higher = pc(VAddrBits-1, offLen+1)
    val target_higher = target(VAddrBits-1, offLen+1)
    val stat = getTargetStatByHigher(pc_higher, target_higher)
    val lower = ZeroExt(getLowerByTarget(target, offLen), this.offsetLen)
    this.lower := lower
    this.tarStat := stat
    this.sharing := isShare.B
  }

  def getTarget(pc: UInt, last_stage: Option[Tuple2[UInt, Bool]] = None) = {
    def getTarget(offLen: Int)(pc: UInt, lower: UInt, stat: UInt,
      last_stage: Option[Tuple2[UInt, Bool]] = None) = {
      val h = pc(VAddrBits-1, offLen+1)
      val higher = Wire(UInt((VAddrBits-offLen-1).W))
      val higher_plus_one = Wire(UInt((VAddrBits-offLen-1).W))
      val higher_minus_one = Wire(UInt((VAddrBits-offLen-1).W))
      if (last_stage.isDefined) {
        val last_stage_pc = last_stage.get._1
        val last_stage_pc_h = last_stage_pc(VAddrBits-1, offLen+1)
        val stage_en = last_stage.get._2
        higher := RegEnable(last_stage_pc_h, stage_en)
        higher_plus_one := RegEnable(last_stage_pc_h+1.U, stage_en)
        higher_minus_one := RegEnable(last_stage_pc_h-1.U, stage_en)
      } else {
        higher := h
        higher_plus_one := h + 1.U
        higher_minus_one := h - 1.U
      }
      val target =
        Cat(
          Mux1H(Seq(
            (stat === TAR_OVF, higher_plus_one),
            (stat === TAR_UDF, higher_minus_one),
            (stat === TAR_FIT, higher),
          )),
          lower(offLen-1, 0), 0.U(1.W)
        )
      require(target.getWidth == VAddrBits)
      require(offLen != 0)
      target
    }
    if (subOffsetLen.isDefined)
      Mux(sharing,
        getTarget(subOffsetLen.get)(pc, lower, tarStat, last_stage),
        getTarget(offsetLen)(pc, lower, tarStat, last_stage)
      )
    else
      getTarget(offsetLen)(pc, lower, tarStat, last_stage)
  }
  def fromAnotherSlot(that: FtbSlot) = {
    require(
      this.offsetLen > that.offsetLen && this.subOffsetLen.map(_ == that.offsetLen).getOrElse(true) ||
      this.offsetLen == that.offsetLen
    )
    this.offset := that.offset
    this.tarStat := that.tarStat
    this.sharing := (this.offsetLen > that.offsetLen && that.offsetLen == this.subOffsetLen.get).B
    this.valid := that.valid
    this.lower := ZeroExt(that.lower, this.offsetLen)
  }
  
}

class FTBEntry(implicit p: Parameters) extends XSBundle with FTBParams with BPUUtils {
  
  
  val valid       = Bool()

  val brSlots = Vec(numBrSlot, new FtbSlot(BR_OFFSET_LEN))

  val tailSlot = new FtbSlot(JMP_OFFSET_LEN, Some(BR_OFFSET_LEN))

  // Partial Fall-Through Address
  val pftAddr     = UInt((log2Up(PredictWidth)+1).W)
  val carry       = Bool()

  val isCall      = Bool()
  val isRet       = Bool()
  val isJalr      = Bool()

  // 
  val oversize    = Bool()

  val last_is_rvc = Bool()

  val always_taken = Vec(numBr, Bool())

  def getSlotForBr(idx: Int): FtbSlot = {
    require(idx <= numBr-1)
    (idx, numBr) match {
      case (i, n) if i == n-1 => this.tailSlot
      case _ => this.brSlots(idx)
    }
  }
  def allSlotsForBr = {
    (0 until numBr).map(getSlotForBr(_))
  }
  def setByBrTarget(brIdx: Int, pc: UInt, target: UInt) = {
    val slot = getSlotForBr(brIdx)
    slot.setLowerStatByTarget(pc, target, brIdx == numBr-1)
  }
  def setByJmpTarget(pc: UInt, target: UInt) = {
    this.tailSlot.setLowerStatByTarget(pc, target, false)
  }

  def getTargetVec(pc: UInt, last_stage: Option[Tuple2[UInt, Bool]] = None) = {
    VecInit((brSlots :+ tailSlot).map(_.getTarget(pc, last_stage)))
  }

  def getOffsetVec = VecInit(brSlots.map(_.offset) :+ tailSlot.offset)
  def isJal = !isJalr
  def getFallThrough(pc: UInt) = getFallThroughAddr(pc, carry, pftAddr)
  def hasBr(offset: UInt) =
    brSlots.map{ s => s.valid && s.offset <= offset}.reduce(_||_) ||
    (tailSlot.valid && tailSlot.offset <= offset && tailSlot.sharing)

  def getBrMaskByOffset(offset: UInt) =
    brSlots.map{ s => s.valid && s.offset <= offset } :+
    (tailSlot.valid && tailSlot.offset <= offset && tailSlot.sharing)
    
  def getBrRecordedVec(offset: UInt) = {
    VecInit(
      brSlots.map(s => s.valid && s.offset === offset) :+
      (tailSlot.valid && tailSlot.offset === offset && tailSlot.sharing)
    )
  }
    
  def brIsSaved(offset: UInt) = getBrRecordedVec(offset).reduce(_||_)

  def brValids = {
    VecInit(
      brSlots.map(_.valid) :+ (tailSlot.valid && tailSlot.sharing)
    )
  }

  def noEmptySlotForNewBr = {
    VecInit(brSlots.map(_.valid) :+ tailSlot.valid).reduce(_&&_)
  }

  def newBrCanNotInsert(offset: UInt) = {
    val lastSlotForBr = tailSlot
    lastSlotForBr.valid && lastSlotForBr.offset < offset
  }

  def jmpValid = {
    tailSlot.valid && !tailSlot.sharing
  }

  def brOffset = {
    VecInit(brSlots.map(_.offset) :+ tailSlot.offset)
  }

  def display(cond: Bool): Unit = {
    XSDebug(cond, p"-----------FTB entry----------- \n")
    XSDebug(cond, p"v=${valid}\n")
    for(i <- 0 until numBr) {
      XSDebug(cond, p"[br$i]: v=${allSlotsForBr(i).valid}, offset=${allSlotsForBr(i).offset}," +
        p"lower=${Hexadecimal(allSlotsForBr(i).lower)}\n")
    }
    XSDebug(cond, p"[tailSlot]: v=${tailSlot.valid}, offset=${tailSlot.offset}," +
      p"lower=${Hexadecimal(tailSlot.lower)}, sharing=${tailSlot.sharing}}\n")
    XSDebug(cond, p"pftAddr=${Hexadecimal(pftAddr)}, carry=$carry\n")
    XSDebug(cond, p"isCall=$isCall, isRet=$isRet, isjalr=$isJalr\n")
    XSDebug(cond, p"oversize=$oversize, last_is_rvc=$last_is_rvc\n")
    XSDebug(cond, p"------------------------------- \n")
  }

}

class FTBEntryWithTag(implicit p: Parameters) extends XSBundle with FTBParams with BPUUtils {
  val entry = new FTBEntry
  val tag = UInt(tagSize.W)
  def display(cond: Bool): Unit = {
    entry.display(cond)
    XSDebug(cond, p"tag is ${Hexadecimal(tag)}\n------------------------------- \n")
  }
}

class FTBMeta(implicit p: Parameters) extends XSBundle with FTBParams {
  val writeWay = UInt(log2Ceil(numWays).W)
  val hit = Bool()
  val pred_cycle = if (!env.FPGAPlatform) Some(UInt(64.W)) else None
}

object FTBMeta {
  def apply(writeWay: UInt, hit: Bool, pred_cycle: UInt)(implicit p: Parameters): FTBMeta = {
    val e = Wire(new FTBMeta)
    e.writeWay := writeWay
    e.hit := hit
    e.pred_cycle.map(_ := pred_cycle)
    e
  }
}

// class UpdateQueueEntry(implicit p: Parameters) extends XSBundle with FTBParams {
//   val pc = UInt(VAddrBits.W)
//   val ftb_entry = new FTBEntry
//   val hit = Bool()
//   val hit_way = UInt(log2Ceil(numWays).W)
// }
//
// object UpdateQueueEntry {
//   def apply(pc: UInt, fe: FTBEntry, hit: Bool, hit_way: UInt)(implicit p: Parameters): UpdateQueueEntry = {
//     val e = Wire(new UpdateQueueEntry)
//     e.pc := pc
//     e.ftb_entry := fe
//     e.hit := hit
//     e.hit_way := hit_way
//     e
//   }
// }

class FTB(implicit p: Parameters) extends BasePredictor with FTBParams with BPUUtils
  with HasCircularQueuePtrHelper with HasPerfEvents {
  override val meta_size = WireInit(0.U.asTypeOf(new FTBMeta)).getWidth

  val ftbAddr = new TableAddr(log2Up(numSets), 1)

  class FTBBank(val numSets: Int, val nWays: Int) extends XSModule with BPUUtils {
    val io = IO(new Bundle {
      val s1_fire = Input(Bool())

      // when ftb hit, read_hits.valid is true, and read_hits.bits is OH of hit way
      // when ftb not hit, read_hits.valid is false, and read_hits is OH of allocWay
      // val read_hits = Valid(Vec(numWays, Bool()))
      val req_pc = Flipped(DecoupledIO(UInt(VAddrBits.W)))
      val read_resp = Output(new FTBEntry)
      val read_hits = Valid(UInt(log2Ceil(numWays).W))

      val u_req_pc = Flipped(DecoupledIO(UInt(VAddrBits.W)))
      val update_hits = Valid(UInt(log2Ceil(numWays).W))
      val update_access = Input(Bool())

      val update_pc = Input(UInt(VAddrBits.W))
      val update_write_data = Flipped(Valid(new FTBEntryWithTag))
      val update_write_way = Input(UInt(log2Ceil(numWays).W))
      val update_write_alloc = Input(Bool())
    })

    // Extract holdRead logic to fix bug that update read override predict read result
    val ftb = Module(new SRAMTemplate(new FTBEntryWithTag, set = numSets, way = numWays, shouldReset = true, holdRead = false, singlePort = true))

    val pred_rdata   = HoldUnless(ftb.io.r.resp.data, RegNext(io.req_pc.valid && !io.update_access))
    ftb.io.r.req.valid := io.req_pc.valid || io.u_req_pc.valid // io.s0_fire
    ftb.io.r.req.bits.setIdx := Mux(io.u_req_pc.valid, ftbAddr.getIdx(io.u_req_pc.bits), ftbAddr.getIdx(io.req_pc.bits)) // s0_idx

    assert(!(io.req_pc.valid && io.u_req_pc.valid))

    io.req_pc.ready := ftb.io.r.req.ready
    io.u_req_pc.ready := ftb.io.r.req.ready

    val req_tag = RegEnable(ftbAddr.getTag(io.req_pc.bits)(tagSize-1, 0), io.req_pc.valid)
    val req_idx = RegEnable(ftbAddr.getIdx(io.req_pc.bits), io.req_pc.valid)

    val u_req_tag = RegEnable(ftbAddr.getTag(io.u_req_pc.bits)(tagSize-1, 0), io.u_req_pc.valid)

    val read_entries = pred_rdata.map(_.entry)
    val read_tags    = pred_rdata.map(_.tag)

    val total_hits = VecInit((0 until numWays).map(b => read_tags(b) === req_tag && read_entries(b).valid && io.s1_fire))
    val hit = total_hits.reduce(_||_)
    // val hit_way_1h = VecInit(PriorityEncoderOH(total_hits))
    val hit_way = OHToUInt(total_hits)

    val u_total_hits = VecInit((0 until numWays).map(b =>
        ftb.io.r.resp.data(b).tag === u_req_tag && ftb.io.r.resp.data(b).entry.valid && RegNext(io.update_access)))
    val u_hit = u_total_hits.reduce(_||_)
    // val hit_way_1h = VecInit(PriorityEncoderOH(total_hits))
    val u_hit_way = OHToUInt(u_total_hits)

    assert(PopCount(total_hits) === 1.U || PopCount(total_hits) === 0.U)
    assert(PopCount(u_total_hits) === 1.U || PopCount(u_total_hits) === 0.U)

    val replacer = ReplacementPolicy.fromString(Some("setplru"), numWays, numSets)
    // val allocWriteWay = replacer.way(req_idx)

    val touch_set = Seq.fill(1)(Wire(UInt(log2Ceil(numSets).W)))
    val touch_way = Seq.fill(1)(Wire(Valid(UInt(log2Ceil(numWays).W))))

    touch_set(0) := req_idx

    touch_way(0).valid := hit
    touch_way(0).bits := hit_way

    replacer.access(touch_set, touch_way)

    // def allocWay(valids: UInt, meta_tags: UInt, req_tag: UInt) = {
    //   val randomAlloc = false
    //   if (numWays > 1) {
    //     val w = Wire(UInt(log2Up(numWays).W))
    //     val valid = WireInit(valids.andR)
    //     val tags = Cat(meta_tags, req_tag)
    //     val l = log2Up(numWays)
    //     val nChunks = (tags.getWidth + l - 1) / l
    //     val chunks = (0 until nChunks).map( i =>
    //       tags(min((i+1)*l, tags.getWidth)-1, i*l)
    //     )
    //     w := Mux(valid, if (randomAlloc) {LFSR64()(log2Up(numWays)-1,0)} else {chunks.reduce(_^_)}, PriorityEncoder(~valids))
    //     w
    //   } else {
    //     val w = WireInit(0.U)
    //     w
    //   }
    // }

    // val allocWriteWay = allocWay(
    //   VecInit(read_entries.map(_.valid)).asUInt,
    //   VecInit(read_tags).asUInt,
    //   req_tag
    // )

    def allocWay(valids: UInt, idx: UInt) = {
      if (numWays > 1) {
        val w = Wire(UInt(log2Up(numWays).W))
        val valid = WireInit(valids.andR)
        w := Mux(valid, replacer.way(idx), PriorityEncoder(~valids))
        w
      }else {
        val w = WireInit(0.U)
        w
      }
    }

    io.read_resp := Mux1H(total_hits, read_entries) // Mux1H
    io.read_hits.valid := hit
    // io.read_hits.bits := Mux(hit, hit_way_1h, VecInit(UIntToOH(allocWriteWay).asBools()))
    io.read_hits.bits := hit_way

    io.update_hits.valid := u_hit
    io.update_hits.bits := u_hit_way

    // XSDebug(!hit, "FTB not hit, alloc a way: %d\n", allocWriteWay)

    // Update logic
    val u_valid = io.update_write_data.valid
    val u_data = io.update_write_data.bits
    val u_idx = ftbAddr.getIdx(io.update_pc)
    val allocWriteWay = allocWay(VecInit(read_entries.map(_.valid)).asUInt, u_idx)
    val u_mask = UIntToOH(Mux(io.update_write_alloc, allocWriteWay, io.update_write_way))

    for (i <- 0 until numWays) {
      XSPerfAccumulate(f"ftb_replace_way$i", u_valid && io.update_write_alloc && OHToUInt(u_mask) === i.U)
      XSPerfAccumulate(f"ftb_replace_way${i}_has_empty", u_valid && io.update_write_alloc && !read_entries.map(_.valid).reduce(_&&_) && OHToUInt(u_mask) === i.U)
      XSPerfAccumulate(f"ftb_hit_way$i", hit && !io.update_access && hit_way === i.U)
    }

    ftb.io.w.apply(u_valid, u_data, u_idx, u_mask)

    // print hit entry info
    Mux1H(total_hits, ftb.io.r.resp.data).display(true.B)
  } // FTBBank

  val ftbBank = Module(new FTBBank(numSets, numWays))

  ftbBank.io.req_pc.valid := io.s0_fire
  ftbBank.io.req_pc.bits := s0_pc

  val ftb_entry = RegEnable(ftbBank.io.read_resp, io.s1_fire)
  val s3_ftb_entry = RegEnable(ftb_entry, io.s2_fire)
  val s1_hit = ftbBank.io.read_hits.valid
  val s2_hit = RegEnable(s1_hit, io.s1_fire)
  val s3_hit = RegEnable(s2_hit, io.s2_fire)
  val writeWay = ftbBank.io.read_hits.bits

  val fallThruAddr = getFallThroughAddr(s2_pc, ftb_entry.carry, ftb_entry.pftAddr)

  // io.out.bits.resp := RegEnable(io.in.bits.resp_in(0), 0.U.asTypeOf(new BranchPredictionResp), io.s1_fire)
  io.out.resp := io.in.bits.resp_in(0)

  val s1_latch_call_is_rvc   = DontCare // TODO: modify when add RAS

  io.out.resp.s2.full_pred.hit       := s2_hit
  io.out.resp.s2.pc                  := s2_pc
  io.out.resp.s2.ftb_entry           := ftb_entry
  io.out.resp.s2.full_pred.fromFtbEntry(ftb_entry, s2_pc, Some((s1_pc, io.s1_fire)))
  io.out.resp.s2.is_minimal := false.B

  io.out.resp.s3.full_pred.hit := s3_hit
  io.out.resp.s3.pc                  := s3_pc
  io.out.resp.s3.ftb_entry           := s3_ftb_entry
  io.out.resp.s3.full_pred.fromFtbEntry(s3_ftb_entry, s3_pc, Some((s2_pc, io.s2_fire)))
  io.out.resp.s3.is_minimal := false.B

  io.out.last_stage_meta := RegEnable(RegEnable(FTBMeta(writeWay.asUInt(), s1_hit, GTimer()).asUInt(), io.s1_fire), io.s2_fire)

  // always taken logic
  for (i <- 0 until numBr) {
    io.out.resp.s2.full_pred.br_taken_mask(i) := io.in.bits.resp_in(0).s2.full_pred.br_taken_mask(i) || s2_hit && ftb_entry.always_taken(i)
    io.out.resp.s3.full_pred.br_taken_mask(i) := io.in.bits.resp_in(0).s3.full_pred.br_taken_mask(i) || s3_hit && s3_ftb_entry.always_taken(i)
  }

  // Update logic
  val update = RegNext(io.update.bits)

  // val update_queue = Mem(64, new UpdateQueueEntry)
  // val head, tail = RegInit(UpdateQueuePtr(false.B, 0.U))
  // val u_queue = Module(new Queue(new UpdateQueueEntry, entries = 64, flow = true))
  // assert(u_queue.io.count < 64.U)

  val u_meta = update.meta.asTypeOf(new FTBMeta)
  val u_valid = RegNext(io.update.valid && !io.update.bits.old_entry)

  // io.s1_ready := ftbBank.io.req_pc.ready && u_queue.io.count === 0.U && !u_valid
  io.s1_ready := ftbBank.io.req_pc.ready && !(u_valid && !u_meta.hit)

  // val update_now = u_queue.io.deq.fire && u_queue.io.deq.bits.hit
  val update_now = u_valid && u_meta.hit

  ftbBank.io.u_req_pc.valid := u_valid && !u_meta.hit
  ftbBank.io.u_req_pc.bits := update.pc

  // assert(!(u_valid && RegNext(u_valid) && update.pc === RegNext(update.pc)))
  // assert(!(u_valid && RegNext(u_valid)))

  // val u_way = u_queue.io.deq.bits.hit_way

  val ftb_write = Wire(new FTBEntryWithTag)
  // ftb_write.entry := Mux(update_now, u_queue.io.deq.bits.ftb_entry, RegNext(u_queue.io.deq.bits.ftb_entry))
  // ftb_write.tag   := ftbAddr.getTag(Mux(update_now, u_queue.io.deq.bits.pc, RegNext(u_queue.io.deq.bits.pc)))(tagSize-1, 0)
  ftb_write.entry := Mux(update_now, update.ftb_entry, RegNext(update.ftb_entry))
  ftb_write.tag   := ftbAddr.getTag(Mux(update_now, update.pc, RegNext(update.pc)))(tagSize-1, 0)

  // val write_valid = update_now || RegNext(u_queue.io.deq.fire && !u_queue.io.deq.bits.hit)
  val write_valid = update_now || RegNext(u_valid && !u_meta.hit)

  // u_queue.io.enq.valid := u_valid
  // u_queue.io.enq.bits := UpdateQueueEntry(update.pc, update.ftb_entry, u_meta.hit, u_meta.writeWay)
  // u_queue.io.deq.ready := RegNext(!u_queue.io.deq.fire || update_now)

  ftbBank.io.update_write_data.valid := write_valid
  ftbBank.io.update_write_data.bits := ftb_write
  // ftbBank.io.update_pc := Mux(update_now, u_queue.io.deq.bits.pc, RegNext(u_queue.io.deq.bits.pc))
  ftbBank.io.update_pc := Mux(update_now, update.pc, RegNext(update.pc))
  ftbBank.io.update_write_way := Mux(update_now, u_meta.writeWay, ftbBank.io.update_hits.bits)
  // ftbBank.io.update_write_alloc := Mux(update_now, !u_queue.io.deq.bits.hit, !ftbBank.io.update_hits.valid)
  ftbBank.io.update_write_alloc := Mux(update_now, false.B, !ftbBank.io.update_hits.valid)
  ftbBank.io.update_access := u_valid && !u_meta.hit
  ftbBank.io.s1_fire := io.s1_fire

  XSDebug("req_v=%b, req_pc=%x, ready=%b (resp at next cycle)\n", io.s0_fire, s0_pc, ftbBank.io.req_pc.ready)
  XSDebug("s2_hit=%b, hit_way=%b\n", s2_hit, writeWay.asUInt)
  XSDebug("s2_br_taken_mask=%b, s2_real_taken_mask=%b\n",
    io.in.bits.resp_in(0).s2.full_pred.br_taken_mask.asUInt, io.out.resp.s2.full_pred.real_slot_taken_mask().asUInt)
  XSDebug("s2_target=%x\n", io.out.resp.s2.getTarget)

  ftb_entry.display(true.B)

  XSPerfAccumulate("ftb_read_hits", RegNext(io.s0_fire) && s1_hit)
  XSPerfAccumulate("ftb_read_misses", RegNext(io.s0_fire) && !s1_hit)

  XSPerfAccumulate("ftb_commit_hits", RegNext(io.update.valid) && u_meta.hit)
  XSPerfAccumulate("ftb_commit_misses", RegNext(io.update.valid) && !u_meta.hit)

  XSPerfAccumulate("ftb_update_req", io.update.valid)
  XSPerfAccumulate("ftb_update_ignored", io.update.valid && io.update.bits.old_entry)
  XSPerfAccumulate("ftb_updated", u_valid)

  override val perfEvents = Seq(
    ("ftb_commit_hits            ", RegNext(io.update.valid)  &&  u_meta.hit),
    ("ftb_commit_misses          ", RegNext(io.update.valid)  && !u_meta.hit),
  )
  generatePerfEvent()
}
