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
import difftest.fpga._
import difftest.fpga.xdma._

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
  val axisDataWidth = 512
  // Top IO: core IO + AXI-stream interface
  val soc = Module(new NutShell()(config))
  val io = IO(new Bundle {
    // Reference clock for HostEndpoint
    val ref_clock = Input(Clock())
    val host_c2h_axis = new AxisMasterBundle(axisDataWidth)
    val core_clock_enable = Output(Bool())
  })
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

  // Instantiate HostEndpoint and diff-test interfaces with reference clock
  val host = withClock(io.ref_clock) { Module(new HostEndpoint()) }
  // Collect diff-test IO (FPGA IO and top IO) in one call
  val (fpgaDifftest, difftest) = {
    val g = DifftestModule.collect("nutshell")
    (g.fpgaIO.get, DifftestModule.createTopIOs(g.exit, g.step))
  }
  // Connect FPGA diff-test signals to HostEndpoint
  host.io.difftest_data   := fpgaDifftest.data
  host.io.difftest_enable := fpgaDifftest.enable
  io.host_c2h_axis <> host.io.host_c2h_axis
  // Preserve control output
  io.core_clock_enable := host.io.core_clock_enable
  // Connect UART interface via top IO
  difftest.uart <> mmio.io.uart
}
