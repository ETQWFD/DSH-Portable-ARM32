// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 cyf112233
package io.github.cyf112233.portable;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Attribution screen.
 *
 * This app is mostly other people's work: the Debian userland, the Node.js
 * runtime, the harness itself, the mobile layout plugin, and the PRoot binary
 * that makes an unprivileged guest possible at all. Each carries its own licence,
 * and two of them (PRoot's GPL-2.0 and libtalloc's LGPL-3.0) require that the
 * licence text travel with the distributed binaries, which is why the full texts
 * are reachable from here rather than summarised.
 */
public class AboutActivity extends Activity {

    /** Components as they ship inside this APK, with the licence each carries. */
    private static final String[][] COMPONENTS = {
        {
            "DSH Portable（本应用）",
            "1.0",
            "GPL-3.0-or-later",
            "https://github.com/cyf112233/DSH-Portable",
            "Android 外壳：解压 rootfs、拉起 PRoot、承载 WebView，以及设置与保活。",
        },
        {
            "DeepSeek Harness (dsh)",
            "0.2.0-rc.2",
            "MIT",
            "https://github.com/deepseek-ai/deepseek-harness",
            "提供 Agent 运行时的主体：web 界面、会话、工具与插件系统。",
        },
        {
            "dsh-web-mobile",
            "3.0.4",
            "MIT",
            "https://github.com/mexiaosqwq/dsh-web-mobile",
            "竖屏 / 触屏适配插件：窄屏布局、侧栏抽屉、会话间距。",
        },
        {
            "PRoot",
            "5.1.107.96 (Termux 构建)",
            "GPL-2.0-or-later",
            "https://proot-me.github.io/",
            "无需 root 的 ptrace 沙箱，让 Debian 根文件系统可以运行。",
        },
        {
            "libandroid-shmem",
            "0.7",
            "BSD-3-Clause",
            "https://github.com/termux/libandroid-shmem",
            "PRoot 依赖的 Android 共享内存实现。",
        },
        {
            "talloc",
            "2.5.0",
            "LGPL-3.0-or-later",
            "https://talloc.samba.org/",
            "PRoot 依赖的分层内存分配器。",
        },
        {
            "Node.js",
            "22.23.3",
            "MIT",
            "https://nodejs.org/",
            "在 Debian 环境内运行 dsh 的 JavaScript 运行时。",
        },
        {
            "Debian GNU/Linux",
            "13 (trixie), arm64",
            "多种自由软件协议",
            "https://www.debian.org/",
            "随包分发的根文件系统，内含各软件包自身的协议。",
        },
    };

    private static final String OWN_GPL3 =
            "DSH Portable — Android 上的 DeepSeek Harness\n"
            + "Copyright (C) 2026 cyf112233\n\n"
            + "本程序是自由软件：你可以依据自由软件基金会发布的 GNU 通用公共许可证\n"
            + "条款（无论第 3 版，或你选择的任何更新版本）重新分发和/或修改它。\n\n"
            + "本程序的分发是希望它有用，但不提供任何担保，甚至不含适销性或\n"
            + "特定用途适用性的默示担保。详见 GNU 通用公共许可证。\n\n"
            + "你应该已经随本程序收到一份 GNU 通用公共许可证副本；如果没有，\n"
            + "见 <https://www.gnu.org/licenses/>。\n\n"
            + "完整协议全文随源码仓库分发（见仓库根目录 LICENSE），\n"
            + "也可在上方「PRoot」一条中查看 GPL 正文。\n\n"
            + "This program is free software: you can redistribute it and/or modify\n"
            + "it under the terms of the GNU General Public License as published by\n"
            + "the Free Software Foundation, either version 3 of the License, or\n"
            + "(at your option) any later version.\n\n"
            + "This program is distributed in the hope that it will be useful,\n"
            + "but WITHOUT ANY WARRANTY; without even the implied warranty of\n"
            + "MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the\n"
            + "GNU General Public License for more details.";

    private static final String MIT_DSH =
            "MIT License\n"
            + "Copyright (c) 2026 DeepSeek\n\n"
            + "Permission is hereby granted, free of charge, to any person obtaining a copy\n"
            + "of this software and associated documentation files (the \"Software\"), to deal\n"
            + "in the Software without restriction, including without limitation the rights\n"
            + "to use, copy, modify, merge, publish, distribute, sublicense, and/or sell\n"
            + "copies of the Software, and to permit persons to whom the Software is\n"
            + "furnished to do so, subject to the following conditions:\n\n"
            + "The above copyright notice and this permission notice shall be included in all\n"
            + "copies or substantial portions of the Software.\n\n"
            + "THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR\n"
            + "IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,\n"
            + "FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE\n"
            + "AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER\n"
            + "LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,\n"
            + "OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE\n"
            + "SOFTWARE.";

    private static final String MIT_MOBILE =
            "MIT License\n"
            + "Copyright (c) 2026 mexiaosqwq\n\n"
            + "Permission is hereby granted, free of charge, to any person obtaining a copy\n"
            + "of this software and associated documentation files (the \"Software\"), to deal\n"
            + "in the Software without restriction, including without limitation the rights\n"
            + "to use, copy, modify, merge, publish, distribute, sublicense, and/or sell\n"
            + "copies of the Software, and to permit persons to whom the Software is\n"
            + "furnished to do so, subject to the following conditions:\n\n"
            + "The above copyright notice and this permission notice shall be included in all\n"
            + "copies or substantial portions of the Software.\n\n"
            + "THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR\n"
            + "IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,\n"
            + "FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE\n"
            + "AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER\n"
            + "LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,\n"
            + "OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE\n"
            + "SOFTWARE.";

    private static final String GPL2 =
            "GNU GENERAL PUBLIC LICENSE\nVersion 2, June 1991\n\n"
            + "Copyright (C) 1989, 1991 Free Software Foundation, Inc.\n"
            + "51 Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA\n\n"
            + "Everyone is permitted to copy and distribute verbatim copies of this license\n"
            + "document, but changing it is not allowed.\n\n"
            + "Preamble\n\n"
            + "The licenses for most software are designed to take away your freedom to share\n"
            + "and change it. By contrast, the GNU General Public License is intended to\n"
            + "guarantee your freedom to share and change free software - to make sure the\n"
            + "software is free for all its users. This General Public License applies to most\n"
            + "of the Free Software Foundation's software and to any other program whose\n"
            + "authors commit to using it. (Some other Free Software Foundation software is\n"
            + "covered by the GNU Lesser General Public License instead.) You can apply it to\n"
            + "your programs, too.\n\n"
            + "When we speak of free software, we are referring to freedom, not price. Our\n"
            + "General Public Licenses are designed to make sure that you have the freedom to\n"
            + "distribute copies of free software (and charge for this service if you wish),\n"
            + "that you receive source code or can get it if you want it, that you can change\n"
            + "the software or use pieces of it in new free programs; and that you know you can\n"
            + "do these things.\n\n"
            + "To protect your rights, we need to make restrictions that forbid anyone to deny\n"
            + "you these rights or to ask you to surrender the rights. These restrictions\n"
            + "translate to certain responsibilities for you if you distribute copies of the\n"
            + "software, or if you modify it.\n\n"
            + "For example, if you distribute copies of such a program, whether gratis or for\n"
            + "a fee, you must give the recipients all the rights that you have. You must make\n"
            + "sure that they, too, receive or can get the source code. And you must show them\n"
            + "these terms so they know their rights.\n\n"
            + "We protect your rights with two steps: (1) copyright the software, and (2)\n"
            + "offer you this license which gives you legal permission to copy, distribute\n"
            + "and/or modify the software.\n\n"
            + "Also, for each author's protection and ours, we want to make certain that\n"
            + "everyone understands that there is no warranty for this free software. If the\n"
            + "software is modified by someone else and passed on, we want its recipients to\n"
            + "know that what they have is not the original, so that any problems introduced by\n"
            + "others will not reflect on the original authors' reputations.\n\n"
            + "Finally, any free program is threatened constantly by software patents. We wish\n"
            + "to avoid the danger that redistributors of a free program will individually\n"
            + "obtain patent licenses, in effect making the program proprietary. To prevent\n"
            + "this, we have made it clear that any patent must be licensed for everyone's\n"
            + "free use or not licensed at all.\n\n"
            + "The precise terms and conditions for copying, distribution and modification\n"
            + "follow.\n\n"
            + "TERMS AND CONDITIONS FOR COPYING, DISTRIBUTION AND MODIFICATION\n\n"
            + "0. This License applies to any program or other work which contains a notice\n"
            + "placed by the copyright holder saying it may be distributed under the terms of\n"
            + "this General Public License. The \"Program\", below, refers to any such program or\n"
            + "work, and a \"work based on the Program\" means either the Program or any\n"
            + "derivative work under copyright law: that is to say, a work containing the\n"
            + "Program or a portion of it, either verbatim or with modifications and/or\n"
            + "translated into another language. (Hereinafter, translation is included without\n"
            + "limitation in the term \"modification\".) Each licensee is addressed as \"you\".\n\n"
            + "Activities other than copying, distribution and modification are not covered by\n"
            + "this License; they are outside its scope. The act of running the Program is not\n"
            + "restricted, and the output from the Program is covered only if its contents\n"
            + "constitute a work based on the Program (independent of having been made by\n"
            + "running the Program). Whether that is true depends on what the Program does.\n\n"
            + "1. You may copy and distribute verbatim copies of the Program's source code as\n"
            + "you receive it, in any medium, provided that you conspicuously and\n"
            + "appropriately publish on each copy an appropriate copyright notice and\n"
            + "disclaimer of warranty; keep intact all the notices that refer to this License\n"
            + "and to the absence of any warranty; and give any other recipients of the\n"
            + "Program a copy of this License along with the Program.\n\n"
            + "You may charge a fee for the physical act of transferring a copy, and you may at\n"
            + "your option offer warranty protection in exchange for a fee.\n\n"
            + "2. You may modify your copy or copies of the Program or any portion of it, thus\n"
            + "forming a work based on the Program, and copy and distribute such modifications\n"
            + "or work under the terms of Section 1 above, provided that you also meet all of\n"
            + "these conditions:\n\n"
            + "    a) You must cause the modified files to carry prominent notices stating that\n"
            + "    you changed the files and the date of any change.\n\n"
            + "    b) You must cause any work that you distribute or publish, that in whole or\n"
            + "    in part contains or is derived from the Program or any part thereof, to be\n"
            + "    licensed as a whole at no charge to all third parties under the terms of\n"
            + "    this License.\n\n"
            + "    c) If the modified program normally reads commands interactively when run,\n"
            + "    you must cause it, when started running for such interactive use in the most\n"
            + "    ordinary way, to print or display an announcement including an appropriate\n"
            + "    copyright notice and a notice that there is no warranty (or else, saying\n"
            + "    that you provide a warranty) and that users may redistribute the program\n"
            + "    under these conditions, and telling the user how to view a copy of this\n"
            + "    License. (Exception: if the Program itself is interactive but does not\n"
            + "    normally print such an announcement, your work based on the Program is not\n"
            + "    required to print an announcement.)\n\n"
            + "These requirements apply to the modified work as a whole. If identifiable\n"
            + "sections of that work are not derived from the Program, and can be reasonably\n"
            + "considered independent and separate works in themselves, then this License, and\n"
            + "its terms, do not apply to those sections when you distribute them as separate\n"
            + "works. But when you distribute the same sections as part of a whole which is a\n"
            + "work based on the Program, the distribution of the whole must be on the terms of\n"
            + "this License, whose permissions for other licensees extend to the entire whole,\n"
            + "and thus to each and every part regardless of who wrote it.\n\n"
            + "Thus, it is not the intent of this section to claim rights or contest your\n"
            + "rights to work written entirely by you; rather, the intent is to exercise the\n"
            + "right to control the distribution of derivative or collective works based on\n"
            + "the Program.\n\n"
            + "In addition, mere aggregation of another work not based on the Program with the\n"
            + "Program (or with a work based on the Program) on a volume of a storage or\n"
            + "distribution medium does not bring the other work under the scope of this\n"
            + "License.\n\n"
            + "3. You may copy and distribute the Program (or a work based on it, under\n"
            + "Section 2) in object code or executable form under the terms of Sections 1 and 2\n"
            + "above provided that you also do one of the following:\n\n"
            + "    a) Accompany it with the complete corresponding machine-readable source\n"
            + "    code, which must be distributed under the terms of Sections 1 and 2 above on\n"
            + "    a medium customarily used for software interchange; or,\n\n"
            + "    b) Accompany it with a written offer, valid for at least three years, to give\n"
            + "    any third party, for a charge no more than your cost of physically performing\n"
            + "    source distribution, a complete machine-readable copy of the corresponding\n"
            + "    source code, to be distributed under the terms of Sections 1 and 2 above on a\n"
            + "    medium customarily used for software interchange; or,\n\n"
            + "    c) Accompany it with the information you received as to the offer to\n"
            + "    distribute corresponding source code. (This alternative is allowed only for\n"
            + "    noncommercial distribution and only if you received the program in object\n"
            + "    code or executable form with such an offer, in accord with Subsection b\n"
            + "    above.)\n\n"
            + "The source code for a work means the preferred form of the work for making\n"
            + "modifications to it. For an executable work, complete source code means all the\n"
            + "source code for all modules it contains, plus any associated interface\n"
            + "definition files, plus the scripts used to control compilation and installation\n"
            + "of the executable. However, as a special exception, the source code distributed\n"
            + "need not include anything that is normally distributed (in either source or\n"
            + "binary form) with the major components (compiler, kernel, and so on) of the\n"
            + "operating system on which the executable runs, unless that component itself\n"
            + "accompanies the executable.\n\n"
            + "If distribution of executable or object code is made by offering access to copy\n"
            + "from a designated place, then offering equivalent access to copy the source code\n"
            + "from the same place counts as distribution of the source code, even though third\n"
            + "parties are not compelled to copy the source along with the object code.\n\n"
            + "4. You may not copy, modify, sublicense, or distribute the Program except as\n"
            + "expressly provided under this License. Any attempt otherwise to copy, modify,\n"
            + "sublicense or distribute the Program is void, and will automatically terminate\n"
            + "your rights under this License. However, parties who have received copies, or\n"
            + "rights, from you under this License will not have their licenses terminated so\n"
            + "long as such parties remain in full compliance.\n\n"
            + "5. You are not required to accept this License, since you have not signed it.\n"
            + "However, nothing else grants you permission to modify or distribute the Program\n"
            + "or its derivative works. These actions are prohibited by law if you do not\n"
            + "accept this License. Therefore, by modifying or distributing the Program (or any\n"
            + "work based on the Program), you indicate your acceptance of this License to do\n"
            + "so, and all its terms and conditions for copying, distributing or modifying the\n"
            + "Program or works based on it.\n\n"
            + "6. Each time you redistribute the Program (or any work based on the Program),\n"
            + "the recipient automatically receives a license from the original licensor to\n"
            + "copy, distribute or modify the Program subject to these terms and conditions.\n"
            + "You may not impose any further restrictions on the recipients' exercise of the\n"
            + "rights granted herein. You are not responsible for enforcing compliance by third\n"
            + "parties to this License.\n\n"
            + "7. If, as a consequence of a court judgment or allegation of patent\n"
            + "infringement or for any other reason (not limited to patent issues), conditions\n"
            + "are imposed on you (whether by court order, agreement or otherwise) that\n"
            + "contradict the conditions of this License, they do not excuse you from the\n"
            + "conditions of this License. If you cannot distribute so as to satisfy\n"
            + "simultaneously your obligations under this License and any other pertinent\n"
            + "obligations, then as a consequence you may not distribute the Program at all.\n"
            + "For example, if a patent license would not permit royalty-free redistribution of\n"
            + "the Program by all those who receive copies directly or indirectly through you,\n"
            + "then the only way you could satisfy both it and this License would be to refrain\n"
            + "entirely from distribution of the Program.\n\n"
            + "If any portion of this section is held invalid or unenforceable under any\n"
            + "particular circumstance, the balance of the section is intended to apply and the\n"
            + "section as a whole is intended to apply in other circumstances.\n\n"
            + "It is not the purpose of this section to induce you to infringe any patents or\n"
            + "other property right claims or to contest validity of any such claims; this\n"
            + "section has the sole purpose of protecting the integrity of the free software\n"
            + "distribution system, which is implemented by public license practices. Many\n"
            + "people have made generous contributions to the wide range of software\n"
            + "distributed through that system in reliance on consistent application of that\n"
            + "system; it is up to the author/donor to decide if he or she is willing to\n"
            + "distribute software through any other system and a licensee cannot impose that\n"
            + "choice.\n\n"
            + "This section is intended to make thoroughly clear what is believed to be a\n"
            + "consequence of the rest of this License.\n\n"
            + "8. If the distribution and/or use of the Program is restricted in certain\n"
            + "countries either by patents or by copyrighted interfaces, the original\n"
            + "copyright holder who places the Program under this License may add an explicit\n"
            + "geographical distribution limitation excluding those countries, so that\n"
            + "distribution is permitted only in or among countries not thus excluded. In such\n"
            + "case, this License incorporates the limitation as if written in the body of this\n"
            + "License.\n\n"
            + "9. The Free Software Foundation may publish revised and/or new versions of the\n"
            + "General Public License from time to time. Such new versions will be similar in\n"
            + "spirit to the present version, but may differ in detail to address new problems\n"
            + "or concerns.\n\n"
            + "Each version is given a distinguishing version number. If the Program specifies\n"
            + "a version number of this License which applies to it and \"any later version\", you\n"
            + "have the option of following the terms and conditions either of that version or\n"
            + "of any later version published by the Free Software Foundation. If the Program\n"
            + "does not specify a version number of this License, you may choose any version\n"
            + "ever published by the Free Software Foundation.\n\n"
            + "10. If you wish to incorporate parts of the Program into other free programs\n"
            + "whose distribution conditions are different, write to the author to ask for\n"
            + "permission. For software which is copyrighted by the Free Software Foundation,\n"
            + "write to the Free Software Foundation; we sometimes make exceptions for this.\n"
            + "Our decision will be guided by the two goals of preserving the free status of\n"
            + "all derivatives of our free software and of promoting the sharing and reuse of\n"
            + "software generally.\n\n"
            + "NO WARRANTY\n\n"
            + "11. BECAUSE THE PROGRAM IS LICENSED FREE OF CHARGE, THERE IS NO WARRANTY FOR THE\n"
            + "PROGRAM, TO THE EXTENT PERMITTED BY APPLICABLE LAW. EXCEPT WHEN OTHERWISE STATED\n"
            + "IN WRITING THE COPYRIGHT HOLDERS AND/OR OTHER PARTIES PROVIDE THE PROGRAM \"AS IS\"\n"
            + "WITHOUT WARRANTY OF ANY KIND, EITHER EXPRESSED OR IMPLIED, INCLUDING, BUT NOT\n"
            + "LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A\n"
            + "PARTICULAR PURPOSE. THE ENTIRE RISK AS TO THE QUALITY AND PERFORMANCE OF THE\n"
            + "PROGRAM IS WITH YOU. SHOULD THE PROGRAM PROVE DEFECTIVE, YOU ASSUME THE COST OF\n"
            + "ALL NECESSARY SERVICING, REPAIR OR CORRECTION.\n\n"
            + "12. IN NO EVENT UNLESS REQUIRED BY APPLICABLE LAW OR AGREED TO IN WRITING WILL\n"
            + "ANY COPYRIGHT HOLDER, OR ANY OTHER PARTY WHO MAY MODIFY AND/OR REDISTRIBUTE THE\n"
            + "PROGRAM AS PERMITTED ABOVE, BE LIABLE TO YOU FOR DAMAGES, INCLUDING ANY GENERAL,\n"
            + "SPECIAL, INCIDENTAL OR CONSEQUENTIAL DAMAGES ARISING OUT OF THE USE OR INABILITY\n"
            + "TO USE THE PROGRAM (INCLUDING BUT NOT LIMITED TO LOSS OF DATA OR DATA BEING\n"
            + "RENDERED INACCURATE OR LOSSES SUSTAINED BY YOU OR THIRD PARTIES OR A FAILURE OF\n"
            + "THE PROGRAM TO OPERATE WITH ANY OTHER PROGRAMS), EVEN IF SUCH HOLDER OR OTHER\n"
            + "PARTY HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH DAMAGES.\n\n"
            + "END OF TERMS AND CONDITIONS";

    private static final String LGPL3_NOTICE =
            "GNU LESSER GENERAL PUBLIC LICENSE\nVersion 3, 29 June 2007\n\n"
            + "Copyright (C) 2007 Free Software Foundation, Inc. <https://fsf.org/>\n"
            + "Everyone is permitted to copy and distribute verbatim copies of this license\n"
            + "document, but changing it is not allowed.\n\n"
            + "This version of the GNU Lesser General Public License incorporates the terms\n"
            + "and conditions of version 3 of the GNU General Public License, supplemented by\n"
            + "the additional permissions listed below.\n\n"
            + "0. Additional Definitions.\n\n"
            + "As used herein, \"this License\" refers to the GNU Lesser General Public License\n"
            + "version 3, and the \"GNU GPL\" refers to version 3 of the GNU General Public\n"
            + "License.\n\n"
            + "\"The Library\" refers to a covered work governed by this License, other than an\n"
            + "Application or a Combined Work as defined below.\n\n"
            + "An \"Application\" is any work that makes use of an interface provided by the\n"
            + "Library, but which is not otherwise based on the Library. Defining a subclass of\n"
            + "a class defined by the Library is deemed a mode of using an interface provided\n"
            + "by the Library.\n\n"
            + "A \"Combined Work\" is a work produced by combining or linking an Application\n"
            + "with the Library. The particular version of the Library with which the Combined\n"
            + "Work was made is also called the \"Linked Version\".\n\n"
            + "The \"Minimal Corresponding Source\" for a Combined Work means the Corresponding\n"
            + "Source for the Combined Work, excluding any source code for portions of the\n"
            + "Combined Work that, considered in isolation, are based on the Application, and\n"
            + "not on the Linked Version.\n\n"
            + "The \"Corresponding Application Code\" for a Combined Work means the object code\n"
            + "and/or source code for the Application, including any data and utility programs\n"
            + "needed for reproducing the Combined Work from the Application, but excluding the\n"
            + "System Libraries of the Combined Work.\n\n"
            + "1. Exception to Section 3 of the GNU GPL.\n\n"
            + "You may convey a covered work under sections 3 and 4 of this License without\n"
            + "being bound by section 3 of the GNU GPL.\n\n"
            + "2. Conveying Modified Versions.\n\n"
            + "If you modify a copy of the Library, and, in your modifications, a facility\n"
            + "refers to a function or data to be supplied by an Application that uses the\n"
            + "facility (other than as an argument passed when the facility is invoked), then\n"
            + "you may convey a copy of the modified version:\n\n"
            + "   a) under this License, provided that you make a good faith effort to ensure\n"
            + "   that, in the event an Application does not supply the function or data, the\n"
            + "   facility still operates, and performs whatever part of its purpose remains\n"
            + "   meaningful, or\n\n"
            + "   b) under the GNU GPL, with none of the additional permissions of this License\n"
            + "   applicable to that copy.\n\n"
            + "3. Object Code Incorporating Material from Library Header Files.\n\n"
            + "The object code form of an Application may incorporate material from a header\n"
            + "file that is part of the Library. You may convey such object code under terms of\n"
            + "your choice, provided that, if the incorporated material is not limited to\n"
            + "numerical parameters, data structure layouts and accessors, or small macros,\n"
            + "inline functions and templates (ten or fewer lines in length), you do both of\n"
            + "the following:\n\n"
            + "   a) Give prominent notice with each copy of the object code that the Library is\n"
            + "   used in it and that the Library and its use are covered by this License.\n\n"
            + "   b) Accompany the object code with a copy of the GNU GPL and this license\n"
            + "   document.\n\n"
            + "4. Combined Works.\n\n"
            + "You may convey a Combined Work under terms of your choice that, taken together,\n"
            + "effectively do not restrict modification of the portions of the Library\n"
            + "contained in the Combined Work and reverse engineering for debugging such\n"
            + "modifications, if you also do each of the following:\n\n"
            + "   a) Give prominent notice with each copy of the Combined Work that the Library\n"
            + "   is used in it and that the Library and its use are covered by this License.\n\n"
            + "   b) Accompany the Combined Work with a copy of the GNU GPL and this license\n"
            + "   document.\n\n"
            + "   c) For a Combined Work that displays copyright notices during execution,\n"
            + "   include the copyright notice for the Library among these notices, as well as\n"
            + "   a reference directing the user to the copies of the GNU GPL and this license\n"
            + "   document.\n\n"
            + "   d) Do one of the following:\n\n"
            + "       0) Convey the Minimal Corresponding Source under the terms of this License,\n"
            + "       and the Corresponding Application Code in a form suitable for, and under\n"
            + "       terms that permit, the user to recombine or relink the Application with a\n"
            + "       modified version of the Linked Version to produce a modified Combined\n"
            + "       Work, in the manner specified by section 6 of the GNU GPL for conveying\n"
            + "       Corresponding Source.\n\n"
            + "       1) Use a suitable shared library mechanism for linking with the Library. A\n"
            + "       suitable mechanism is one that (a) uses at run time a copy of the Library\n"
            + "       already present on the user's computer system, and (b) will operate\n"
            + "       properly with a modified version of the Library that is interface-\n"
            + "       compatible with the Linked Version.\n\n"
            + "   e) Provide Installation Information, but only if you would otherwise be\n"
            + "   required to provide such information under section 6 of the GNU GPL, and only\n"
            + "   to the extent that such information is necessary to install and execute a\n"
            + "   modified version of the Combined Work produced by recombining or relinking\n"
            + "   the Application with a modified version of the Linked Version.\n\n"
            + "5. Combined Libraries.\n\n"
            + "You may place library facilities that are a work based on the Library side by\n"
            + "side in a single library together with other library facilities that are not\n"
            + "Applications and are not covered by this License, and convey such a combined\n"
            + "library under terms of your choice, if you do both of the following:\n\n"
            + "   a) Accompany the combined library with a copy of the same work based on the\n"
            + "   Library, uncombined with any other library facilities, conveyed under the\n"
            + "   terms of this License.\n\n"
            + "   b) Give prominent notice with the combined library that part of it is a work\n"
            + "   based on the Library, and explaining where to find the accompanying uncombined\n"
            + "   form of the same work.\n\n"
            + "6. Revised Versions of the GNU Lesser General Public License.\n\n"
            + "The Free Software Foundation may publish revised and/or new versions of the GNU\n"
            + "Lesser General Public License from time to time. Such new versions will be\n"
            + "similar in spirit to the present version, but may differ in detail to address\n"
            + "new problems or concerns.\n\n"
            + "Each version is given a distinguishing version number. If the Library as you\n"
            + "received it specifies that a certain numbered version of the GNU Lesser General\n"
            + "Public License \"or any later version\" applies to it, you have the option of\n"
            + "following the terms and conditions either of that published version or of any\n"
            + "later version published by the Free Software Foundation. If the Library as you\n"
            + "received it does not specify a version number of the GNU Lesser General Public\n"
            + "License, you may choose any version of the GNU Lesser General Public License\n"
            + "ever published by the Free Software Foundation.\n\n"
            + "If the Library as you received it specifies that a proxy can decide which future\n"
            + "versions of the GNU Lesser General Public License can be used, that proxy's\n"
            + "public statement of acceptance of a version permanently authorizes you to choose\n"
            + "that version for the Library.";

    private static final String BSD3 =
            "BSD 3-Clause License\n\n"
            + "Copyright (c) 2013, Sergii Pylypenko\n"
            + "Copyright (c) 2017, Fredrik Fornwall\n"
            + "All rights reserved.\n\n"
            + "Redistribution and use in source and binary forms, with or without\n"
            + "modification, are permitted provided that the following conditions are met:\n\n"
            + "1. Redistributions of source code must retain the above copyright notice, this\n"
            + "   list of conditions and the following disclaimer.\n\n"
            + "2. Redistributions in binary form must reproduce the above copyright notice,\n"
            + "   this list of conditions and the following disclaimer in the documentation\n"
            + "   and/or other materials provided with the distribution.\n\n"
            + "3. Neither the name of the copyright holder nor the names of its contributors\n"
            + "   may be used to endorse or promote products derived from this software without\n"
            + "   specific prior written permission.\n\n"
            + "THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS \"AS IS\" AND\n"
            + "ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED\n"
            + "WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE\n"
            + "DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR\n"
            + "ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES\n"
            + "(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;\n"
            + "LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON\n"
            + "ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT\n"
            + "(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS\n"
            + "SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.";

    /** Licence texts for the components whose terms require redistribution. */
    private static final String[][] LICENSES = {
        { "GPL-3.0 (DSH Portable，本应用)", OWN_GPL3 },
        { "MIT License (DeepSeek Harness)", MIT_DSH },
        { "MIT License (dsh-web-mobile)", MIT_MOBILE },
        { "GNU General Public License v2 (PRoot)", GPL2 },
        { "GNU Lesser General Public License v3 (talloc)", LGPL3_NOTICE },
        { "BSD 3-Clause License (libandroid-shmem)", BSD3 },
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        String version;
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            version = "?";
        }

        renderHeader(version);

        ((Button) findViewById(R.id.btnViewLicenses)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPickList("开源协议全文", LICENSES);
            }
        });
        ((Button) findViewById(R.id.btnViewNotices)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showText("版权声明", buildNotices());
            }
        });
    }

    /** Fills the header and builds one card per bundled component. */
    private void renderHeader(String version) {
        TextView versionView = (TextView) findViewById(R.id.aboutVersion);
        versionView.setText("v" + version + " · Android " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + ")");

        TextView summary = (TextView) findViewById(R.id.aboutSummary);
        summary.setText("把完整的 DeepSeek Harness 跑在手机本地。APK 内置 Debian arm64 "
                + "根文件系统与 Node.js，通过 PRoot 以普通应用权限启动（无需 root），"
                + "界面由 WebView 访问 127.0.0.1 上的标准 dsh web 服务。");

        LinearLayout container = (LinearLayout) findViewById(R.id.aboutProjects);
        container.removeAllViews();
        for (String[] component : COMPONENTS) {
            container.addView(buildProjectCard(component));
        }
    }

    /** One project card: name, version chip, licence, link, and its role here. */
    private View buildProjectCard(String[] component) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = dp(10);
        card.setLayoutParams(cardParams);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView name = new TextView(this);
        name.setText(component[0]);
        name.setTextSize(14f);
        name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        name.setTextColor(getResources().getColor(R.color.bar_fg));
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        name.setLayoutParams(nameParams);
        titleRow.addView(name);

        TextView version = new TextView(this);
        version.setText(component[1]);
        version.setTextSize(10.5f);
        version.setTextColor(getResources().getColor(R.color.accent));
        version.setBackgroundResource(R.drawable.bg_chip);
        version.setPadding(dp(7), dp(3), dp(7), dp(3));
        titleRow.addView(version);

        card.addView(titleRow);

        TextView licence = new TextView(this);
        licence.setText(component[2]);
        licence.setTextSize(11.5f);
        licence.setTextColor(getResources().getColor(R.color.ok));
        licence.setPadding(0, dp(7), 0, 0);
        card.addView(licence);

        TextView role = new TextView(this);
        role.setText(component[4]);
        role.setTextSize(11.5f);
        role.setLineSpacing(dp(3), 1f);
        role.setTextColor(getResources().getColor(R.color.bar_fg_dim));
        role.setPadding(0, dp(4), 0, 0);
        card.addView(role);

        final String link = component[3];
        TextView url = new TextView(this);
        url.setText(link);
        url.setTextSize(11f);
        url.setTextColor(getResources().getColor(R.color.accent));
        url.setPadding(0, dp(7), 0, 0);
        url.setPaintFlags(url.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        url.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link)));
                } catch (Exception e) {
                    // No browser installed; the URL stays selectable in the card.
                }
            }
        });
        card.addView(url);

        return card;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private String buildNotices() {
        StringBuilder builder = new StringBuilder();
        builder.append("DSH Portable（本应用）1.0\n")
                .append("Copyright (C) 2026 cyf112233 — GPL-3.0-or-later\n\n")
                .append("DeepSeek Harness (dsh) 0.2.0-rc.2\n")
                .append("Copyright (c) 2026 DeepSeek — MIT\n\n")
                .append("dsh-web-mobile 3.0.4\n")
                .append("Copyright (c) 2026 mexiaosqwq — MIT\n\n")
                .append("PRoot 5.1.107.96\n")
                .append("Copyright (C) 2009-2016 STMicroelectronics — GPL-2.0-or-later\n")
                .append("Termux 打包版本，取自 packages.termux.dev。\n\n")
                .append("libandroid-shmem 0.7\n")
                .append("Copyright (c) 2013 Sergii Pylypenko, 2017 Fredrik Fornwall — BSD-3-Clause\n\n")
                .append("talloc 2.5.0\n")
                .append("Copyright (C) 2004-2010 Andrew Tridgell — LGPL-3.0-or-later\n\n")
                .append("Node.js 22.23.3\n")
                .append("Copyright (c) Node.js contributors — MIT\n\n")
                .append("Debian GNU/Linux 13 (trixie), arm64\n")
                .append("Copyright (c) 1993-2026 Software in the Public Interest, Inc.\n")
                .append("根文件系统内的各软件包按其自身协议分发。\n");
        return builder.toString();
    }

    private void showPickList(String title, final String[][] entries) {
        String[] labels = new String[entries.length];
        for (int i = 0; i < entries.length; i++) {
            labels[i] = entries[i][0];
        }
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(labels, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        showText(entries[which][0], entries[which][1]);
                    }
                })
                .setNegativeButton(R.string.about_close, null)
                .show();
    }

    private void showText(String title, String content) {
        TextView view = new TextView(this);
        view.setText(content);
        view.setTextSize(10.5f);
        view.setTypeface(android.graphics.Typeface.MONOSPACE);
        view.setTextColor(getResources().getColor(R.color.bar_fg));
        view.setTextIsSelectable(true);
        int pad = dp(14);
        view.setPadding(pad, pad, pad, pad);

        android.widget.ScrollView scroller = new android.widget.ScrollView(this);
        scroller.addView(view);
        scroller.setBackgroundColor(getResources().getColor(R.color.scrim));

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(scroller)
                .setPositiveButton(R.string.about_close, null)
                .show();
    }

}
