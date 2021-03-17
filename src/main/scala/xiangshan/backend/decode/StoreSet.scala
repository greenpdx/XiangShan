package xiangshan.backend.decode

import chisel3._
import chisel3.util._
import xiangshan._
import utils._

// store set load violation predictor
// See "Memory Dependence Prediction using Store Sets" for details

// Store Set Identifier Table Entry
class SSITEntry extends XSBundle with MemPredParameters {
  val valid = Bool()
  val isload = Bool()
  val ssid = UInt(SSIDWidth.W) // store set identifier
}

// Store Set Identifier Table
class SSIT extends XSModule with MemPredParameters {
  val io = IO(new Bundle {
    val raddr = Vec(DecodeWidth, Input(UInt(MemPredPCWidth.W))) // xor hashed decode pc(VaddrBits-1, 1)
    val rdata = Vec(DecodeWidth, Output(new SSITEntry))
    val update = Input(new MemPredUpdateReq) // RegNext should be added outside
    val csrCtrl = Input(new CustomCSRCtrlIO)
  })

  // TODO: use MemTemplate
  val valid = RegInit(VecInit(Seq.fill(SSITSize)(false.B)))
  val isload = Reg(Vec(SSITSize, Bool()))
  val ssid = Reg(Vec(SSITSize, UInt(SSIDWidth.W)))

  val resetCounter = RegInit(0.U(ResetTimeMax2Pow.W))
  resetCounter := resetCounter + 1.U

  // read SSIT in rename stage
  for (i <- 0 until DecodeWidth) {
    // io.rdata(i) := (data(io.raddr(i))(1) || io.csrCtrl.no_spec_load) && !io.csrCtrl.lvpred_disable
    io.rdata(i).valid := valid(io.raddr(i))
    io.rdata(i).isload := isload(io.raddr(i))
    io.rdata(i).ssid := ssid(io.raddr(i))
  }

  // update SSIT if load violation redirect is detected

  // update stage -1
  // when io.update.valid, we should RegNext() it for at least 1 cycle
  // outside of SSIT.

  // update stage 0
  // RegNext(io.update) while reading SSIT entry for necessary information
  val MemPredUpdateReqReg = RegEnable(io.update, enable = io.update.valid)
  // load has already been assigned with a store set
  val loadAssigned = RegNext(valid(io.update.ldpc))
  val loadOldSSID = RegNext(ssid(io.update.ldpc))
  // store has already been assigned with a store set
  val storeAssigned = RegNext(valid(io.update.stpc))
  val storeOldSSID = RegNext(ssid(io.update.stpc))
  // both the load and the store have already been assigned store sets
  // but load's store set ID is smaller
  val loadIsWinner = RegNext(io.update.ldpc < io.update.stpc)
  val winnerSSID = Mux(loadIsWinner, loadOldSSID, storeOldSSID)

  // for now we just use lowest bits of ldpc as store set id
  val ssidAllocate = MemPredUpdateReqReg.ldpc(SSIDWidth-1, 0)

  // update stage 1
  when(MemPredUpdateReqReg.valid){
    switch (Cat(loadAssigned, storeAssigned)) {
      // 1. "If neither the load nor the store has been assigned a store set, 
      // one is allocated and assigned to both instructions."
      is (Cat(false.B, false.B)) {
        valid(MemPredUpdateReqReg.ldpc) := true.B
        isload(MemPredUpdateReqReg.ldpc) := true.B
        ssid(MemPredUpdateReqReg.ldpc) := ssidAllocate
        valid(MemPredUpdateReqReg.stpc) := true.B
        isload(MemPredUpdateReqReg.stpc) := false.B
        ssid(MemPredUpdateReqReg.stpc) := ssidAllocate
      }
      // 2. "If the load has been assigned a store set, but the store has not, 
      // the store is assigned the load’s store set."
      is (Cat(true.B, false.B)) {
        valid(MemPredUpdateReqReg.stpc) := true.B
        isload(MemPredUpdateReqReg.stpc) := false.B
        ssid(MemPredUpdateReqReg.stpc) := loadOldSSID
      }
      // 3. "If the store has been assigned a store set, but the load has not, 
      // the load is assigned the store’s store set."
      is (Cat(false.B, true.B)) {
        valid(MemPredUpdateReqReg.ldpc) := true.B
        isload(MemPredUpdateReqReg.ldpc) := true.B
        ssid(MemPredUpdateReqReg.ldpc) := storeOldSSID
      }
      // 4. "If both the load and the store have already been assigned store sets, 
      // one of the two store sets is declared the "winner". 
      // The instruction belonging to the loser’s store set is assigned the winner’s store set."
      is (Cat(true.B, true.B)) {
        valid(MemPredUpdateReqReg.ldpc) := true.B
        isload(MemPredUpdateReqReg.ldpc) := true.B
        ssid(MemPredUpdateReqReg.ldpc) := winnerSSID
        valid(MemPredUpdateReqReg.stpc) := true.B
        isload(MemPredUpdateReqReg.stpc) := false.B
        ssid(MemPredUpdateReqReg.stpc) := winnerSSID
      }
    }
  }

  // reset period: ResetTimeMax2Pow
  when(resetCounter(ResetTimeMax2Pow-1, ResetTimeMin2Pow)(RegNext(io.csrCtrl.waittable_timeout))) {
    for (j <- 0 until SSITSize) {
      valid(j) := 0.U
    }
    resetCounter:= 0.U
  }

  // debug
  for (i <- 0 until StorePipelineWidth) {
    when (MemPredUpdateReqReg.valid) {
      XSDebug("%d: SSIT update: load pc %x store pc %x\n", GTimer(), MemPredUpdateReqReg.ldpc, MemPredUpdateReqReg.stpc)
      XSDebug("%d: SSIT update: load valid %b ssid %x  store valid %b ssid %x\n", GTimer(), loadAssigned, loadOldSSID, storeAssigned,storeOldSSID)
    }
  }
}


// class StoreSet extends XSModule with MemPredParameters {
//   val io = IO(new Bundle {
//   })
// }
