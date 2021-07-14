/***************************************************************************************
* Copyright (c) 2020-2021 Institute of Computing Technology, Chinese Academy of Sciences
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

#include <functional>
#include <locale.h>
#include <csignal>
#include "emu.h"
#include "difftest.h"
#include "device.h"
#include "goldenmem.h"
#include "remote_bitbang.h"


static char mybuf[BUFSIZ];

extern remote_bitbang_t * jtag;

// junk, link for verilator
std::function<double()> get_sc_time_stamp = []() -> double { return 0; };
double sc_time_stamp() { return get_sc_time_stamp(); }

int main(int argc, const char** argv) {
  printf("Emu compiled at %s, %s\n", __DATE__, __TIME__);

  jtag = new remote_bitbang_t(23333);

  setbuf(stderr, mybuf);

  // enable thousands separator for printf()
  setlocale(LC_NUMERIC, "");

  if (signal(SIGINT, sig_handler) == SIG_ERR) {
    printf("\ncan't catch SIGINT\n");
  }

  auto emu = new Emulator(argc, argv);

  get_sc_time_stamp = [&emu]() -> double {
    return emu->get_cycles();
  };

  auto args = emu->get_args();
  uint64_t cycles = emu->execute(args.max_cycles, args.max_instr);
  bool is_good_trap = emu->is_good_trap();
  delete emu;

  extern uint32_t uptime(void);
  uint32_t ms = uptime();

  eprintf(ANSI_COLOR_BLUE "Seed=%d Guest cycle spent: %'" PRIu64
      " (this will be different from cycleCnt if emu loads a snapshot)\n" ANSI_COLOR_RESET, args.seed, cycles);
  eprintf(ANSI_COLOR_BLUE "Host time spent: %'dms\n" ANSI_COLOR_RESET, ms);

  return !is_good_trap;
}
