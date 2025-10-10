/**************************************************************************************
* Copyright (c) 2020 Institute of Computing Technology, CAS
* Copyright (c) 2020 University of Chinese Academy of Sciences
*
* NutShell is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*             http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND, EITHER
* EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT, MERCHANTABILITY OR
* FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

package sim

import bus.axi4._
import chisel3._
import device.AXI4RAM
import difftest._
import nutcore.NutCoreConfig
import system._
import xdma._

class SimTop extends Module {
  lazy val config = NutCoreConfig(FPGAPlatform = false)
  val soc = Module(new NutShell()(config))
  val mem = Module(new AXI4RAM(memByte = 2L * 1024 * 1024 * 1024, useBlackBox = true))
  // Be careful with the commit checking of emu.
  // A large delay will make emu incorrectly report getting stuck.
  val memdelay = Module(new AXI4Delayer(0))
  val mmio = Module(new SimMMIO)

  soc.io.frontend <> mmio.io.dma

  memdelay.io.in <> soc.io.mem
  mem.io.in <> memdelay.io.out

  mmio.io.rw <> soc.io.mmio

  soc.io.meip := mmio.io.meip

  val difftest = DifftestModule.finish("nutshell")
  difftest.uart <> mmio.io.uart
}

class FpgaSimTop extends Module {
  override lazy val desiredName = "SimTop"
  lazy val config = NutCoreConfig(FPGAPlatform = false)
  val io = IO(new Bundle {
    // 512-bit AXIS
    val xdma_axis = new xdma.AxisBundle(dataWidth = 512)
    val xdma_reset_cpu = Input(Bool())
  })
  val globalReset = Wire(Reset())
  globalReset := reset.asBool || io.xdma_reset_cpu

  // instantiate SoC under the combined reset
  val soc = withReset(globalReset) {
    Module(new NutShell()(config))
  }
  val mem = Module(new AXI4RAM(memByte = 2L * 1024 * 1024 * 1024, useBlackBox = true))
  // Be careful with the commit checking of emu.
  // A large delay will make emu incorrectly report getting stuck.
  val memdelay = Module(new AXI4Delayer(0))
  val mmio = Module(new SimMMIO)

  val xdma_axis2axi4 = Module(new xdma.XDMA_AxisToAxi4())
  xdma_axis2axi4.io.axis <> io.xdma_axis

  val axi4_arbiter = Module(new xdma.AXI4Arbiter())
  axi4_arbiter.io.in(1) <> soc.io.mem
  axi4_arbiter.io.in(0).aw <> xdma_axis2axi4.io.aw
  axi4_arbiter.io.in(0).w  <> xdma_axis2axi4.io.w
  axi4_arbiter.io.in(0).b  <> xdma_axis2axi4.io.b
  axi4_arbiter.io.in(0).ar.valid := false.B
  axi4_arbiter.io.in(0).ar.bits  := 0.U.asTypeOf(axi4_arbiter.io.in(0).ar.bits)
  axi4_arbiter.io.in(0).r.ready  := false.B
  axi4_arbiter.io.in(0).ar <> DontCare
  axi4_arbiter.io.in(0).r  <> DontCare

  axi4_arbiter.io.out <>  memdelay.io.in

  soc.io.frontend <> mmio.io.dma

  mem.io.in <> memdelay.io.out

  mmio.io.rw <> soc.io.mmio

  soc.io.meip := mmio.io.meip

  val difftest = DifftestModule.finish("nutshell")
  difftest.uart <> mmio.io.uart
}
