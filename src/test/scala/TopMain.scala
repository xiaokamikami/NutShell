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

package top

import chisel3._
import chisel3.stage.ChiselGeneratorAnnotation
import circt.stage._
import device.AXI4VGA
import difftest.DifftestModule
import difftest.fpga._
import difftest.fpga.xdma._
import nutcore.NutCoreConfig
import sim.{SimTop,FpgaSimTop}
import system.NutShell

class Top extends Module {
  val io = IO(new Bundle{})
  val nutshell = Module(new NutShell()(NutCoreConfig()))
  val vga = Module(new AXI4VGA)

  nutshell.io := DontCare
  vga.io := DontCare
  dontTouch(nutshell.io)
  dontTouch(vga.io)
}

class FpgaDiffTop extends Module {
  override lazy val desiredName = "SimTop"
  lazy val config = NutCoreConfig(FPGADifftest = true)
  val soc = Module(new NutShell()(config))
  // AXI-stream data width for HostEndpoint (match default HostEndpoint)
  val axisDataWidth = 512
  // Top IO: core IO + AXI-stream interface
  val io = IO(new Bundle {
    val core = soc.io.cloneType
    // Sink AXI-stream interface: host endpoint drives host_c2h_axis
    val host_c2h_axis = new AxisMasterBundle(axisDataWidth)
    // Expose core clock enable from HostEndpoint
    val core_clock_enable = Output(Bool())
  })
  // Connect core IO
  soc.io <> io.core

    // Instantiate HostEndpoint to wrap VerilogDifftest2AXI
  val host = Module(new HostEndpoint())
  // Collect FPGA diff-test IO bundle and expose as IO
  val difftest = DifftestModule.collect("nutshell")
  val fpgaDifftest = difftest.fpgaIO.get
  // Connect diff-test data and enable to HostEndpoint without exposing top IO
  host.io.difftest_data   := fpgaDifftest.data
  host.io.difftest_enable := fpgaDifftest.enable
  io.host_c2h_axis <> host.io.host_c2h_axis
  // Preserve control output
  io.core_clock_enable := host.io.core_clock_enable

  dontTouch(io.core)
}

object TopMain extends App {
  def parseArgs(info: String, args: Array[String]): String = {
    var target = ""
    for (arg <- args) { if (arg.startsWith(info + "=") == true) { target = arg } }
    require(target != "")
    target.substring(info.length()+1)
  }
  val (newArgs, firtoolOptions) = DifftestModule.parseArgs(args)
  val board = parseArgs("BOARD", newArgs)
  val core = parseArgs("CORE", newArgs)

  val s = (board match {
    case "sim"    => Nil
    case "pynq"   => PynqSettings()
    case "axu3cg" => Axu3cgSettings()
    case "fpgadiff" => FpgaDiffSettings()
    case "fpgasim"  => Nil
    case "PXIe"   => PXIeSettings()
  } ) ++ ( core match {
    case "inorder"  => InOrderSettings()
    case "ooo"  => OOOSettings()
    case "embedded"=> EmbededSettings()
  } )
  s.foreach{Settings.settings += _} // add and overwrite DefaultSettings
  println("====== Settings = (" + board + ", " +  core + ") ======")
  Settings.settings.toList.sortBy(_._1)(Ordering.String).foreach {
    case (f, v: Long) =>
      println(f + " = 0x" + v.toHexString)
    case (f, v) =>
      println(f + " = " + v)
  }

  val generator = if (board == "sim") {
    ChiselGeneratorAnnotation(() => new SimTop)
  } else if (board == "fpgadiff") {
    ChiselGeneratorAnnotation(() => new FpgaDiffTop)
  } else if (board == "fpgasim") {
    ChiselGeneratorAnnotation(() => new FpgaSimTop)
  }
  else {
    ChiselGeneratorAnnotation(() => new Top)
  }
  var exe_args = newArgs.filter{
    value => value.forall(char => char!='=')
  }
  (new ChiselStage).execute(newArgs, Seq(generator) ++ firtoolOptions
    :+ CIRCTTargetAnnotation(CIRCTTarget.SystemVerilog)
    :+ FirtoolOption("--disable-annotation-unknown")
    :+ FirtoolOption("--default-layer-specialization=enable")
  )
}