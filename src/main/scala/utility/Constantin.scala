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

package utility

import chisel3._
import chisel3.util._

trait ConstantinParams {
  def UIntWidth = 64
  def AutoSolving = false
  def getdpicFunc(constName: String) = {
    s"${constName}_constantin_read"
  }
  def getModuleName(constName: String) = {
    s"${constName}_constantinReader"
  }
}

private class SignalReadHelper(constName: String) extends BlackBox with HasBlackBoxInline with ConstantinParams {
  val io = IO(new Bundle{
    //val clock = Input(Clock())
    //val reset = Input(Reset())
    val value = Output(UInt(UIntWidth.W))
  })

  val moduleName = getModuleName(constName)
  val dpicFunc = getdpicFunc(constName)

  val verilog =
    s"""
       |import "DPI-C" function longint $dpicFunc();
       |
       |module $moduleName(
       |  output [$UIntWidth - 1:0] value
       |);
       |
       |  assign value = $dpicFunc();
       |endmodule
       |""".stripMargin
  setInline(s"$moduleName.v", verilog)
  override def desiredName: String = moduleName
}

class MuxModule[A <: Record](gen: A, n: Int) extends Module {
  val io = IO(new Bundle{
    val in = Flipped(Vec(n, gen))
    val sel = Input(UInt(log2Ceil(n).W))
    val out = gen
  })
  io.in.foreach(t => t <> DontCare)
  io.out <> io.in(0)
  io.in.zipWithIndex.map{case (t, i) => when (io.sel === i.U) {io.out <> t}}
}

/*
* File format: constName expected_runtimevalue (unsigned DEC)
* */

object Constantin extends ConstantinParams {
  private val recordMap = scala.collection.mutable.Map[String, UInt]()
  private val objectName = "constantin"
  private var envInFPGA = false

  def init(envInFPGA: Boolean): Unit = {
    this.envInFPGA = envInFPGA
  }

  def createRecord(constName: String, initValue: UInt = 0.U): UInt = {
    val t = WireInit(initValue.asTypeOf(UInt(UIntWidth.W)))
    if (recordMap.contains(constName)) {
      recordMap.getOrElse(constName, 0.U)
    } else {
      recordMap += (constName -> t)
       if (envInFPGA) {
         println(s"Constantin initRead: ${constName} = ${initValue}")
         recordMap.getOrElse(constName, 0.U)
       } else {
        val recordModule = Module(new SignalReadHelper(constName))
        //recordModule.io.clock := Clock()
        //recordModule.io.reset := Reset()
        t := recordModule.io.value

        // print record info
         println(s"Constantin fileRead: ${constName} = ${initValue}")
        t
       }
    }
  }

  def getCHeader: String = {
    s"""
       |#ifndef CONSTANTIN_H
       |#define CONSTANTIN_H
       |
       |#endif // CONSTANTIN_H
       |""".stripMargin
  }

  def getPreProcessCpp: String = {
    s"""
       |#include <iostream>
       |#include <fstream>
       |#include <map>
       |#include <string>
       |#include <string>
       |#include <cstdlib>
       |#include <stdint.h>
       |using namespace std;
       |
       |fstream cf;
       |map<string, uint64_t> constantinMap;
       |void constantinLoad() {
       |  uint64_t num;
       |  string tmp;
       |  string noop_home = getenv("NOOP_HOME");
       |  tmp = noop_home + "/build/${objectName}.txt";
       |  cf.open(tmp.c_str(), ios::in);
       |  while (!cf.eof()) {
       |    cf>>tmp>>num;
       |    constantinMap[tmp] = num;
       |  }
       |  cf.close();
       |
       |}
       |""".stripMargin + recordMap.map({a => getCpp(a._1)}).foldLeft("")(_ + _)
  }
  def getPreProcessFromStdInCpp: String = {
    s"""
       |#include <iostream>
       |#include <fstream>
       |#include <map>
       |#include <string>
       |#include <string>
       |#include <cstdlib>
       |#include <stdint.h>
       |using namespace std;
       |
       |fstream cf;
       |map<string, uint64_t> constantinMap;
       |void constantinLoad() {
       |  uint64_t num;
       |  string tmp;
       |  uint64_t total_num;
       |  cout << "please input total constant number" << endl;
       |  cin >> total_num;
       |  cout << "please input each constant ([constant name] [value])" << endl;
       |  for(int i=0; i<total_num; i++) {
       |    cin >> tmp >> num;
       |    constantinMap[tmp] = num;
       |  }
       |
       |}
       |""".stripMargin + recordMap.map({a => getCpp(a._1)}).foldLeft("")(_ + _)
  }
  def getCpp(constName: String): String = {
    s"""
       |#include <map>
       |#include <string>
       |#include <stdint.h>
       |#include <assert.h>
       |using namespace std;
       |extern map<string, uint64_t> constantinMap;
       |extern "C" uint64_t ${getdpicFunc(constName)}() {
       |  assert(constantinMap.find("${constName}")!=constantinMap.end() && "${constName} not found in constantinMap");
       |  // output constantin different result
       |  static int a = -1;
       |  if(a != constantinMap["${constName}"]){
       |    cout << "${constName}" << " = " << constantinMap["${constName}"] << endl;
       |    a = constantinMap["${constName}"];
       |  }
       |  return constantinMap["${constName}"];
       |}
       |""".stripMargin
  }

  def getTXT: String = {
    recordMap.map({a => a._1 +" 0\n"}).foldLeft("")(_ + _)
  }

  def addToFileRegisters = {
    FileRegisters.add(s"${objectName}.hpp", getCHeader)
    if(AutoSolving) {
      FileRegisters.add(s"${objectName}.cpp", getPreProcessFromStdInCpp)
    }else {
      FileRegisters.add(s"${objectName}.cpp", getPreProcessCpp)
    }
    FileRegisters.add(s"${objectName}.txt", getTXT)
  }

}