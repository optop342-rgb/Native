import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.mem.MemoryBlock;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TraceMinecraftStringsNoAnalysis extends GhidraScript {
    private static final String[] NEEDLES = {
        "CommandParseTables", "SlashCommandExecuted", "commands.execute.description",
        "CommandOrigin did not have a BlockSource", "ActorSetPosSystem",
        "ActorSetPositionRequestComponent", "TickSetPositionDimensionChunkMove",
        "LocalPlayerPostMoveSystem", "PlayerLocationSender", "CameraSetPositionSystem",
        "CameraSetRotationSystem", "Actor _setPos", "Actor setPos"
    };

    @Override
    public void run() throws Exception {
        Map<String, Set<Address>> strings = new LinkedHashMap<>();
        for (String needle : NEEDLES) {
            strings.put(needle, new LinkedHashSet<>());
        }
        scanStrings(strings);

        Map<Long, List<Map.Entry<String, Address>>> targetsByPage = new LinkedHashMap<>();
        for (Map.Entry<String, Set<Address>> entry : strings.entrySet()) {
            for (Address target : entry.getValue()) {
                println("STRING\t" + target + "\t" + entry.getKey());
                targetsByPage.computeIfAbsent(target.getOffset() & ~0xfffL,
                    ignored -> new ArrayList<>()).add(Map.entry(entry.getKey(), target));
            }
        }
        println("IMAGE_BASE\t" + currentProgram.getImageBase());
        scanCode(targetsByPage);
    }

    private void scanStrings(Map<String, Set<Address>> strings) throws Exception {
        for (MemoryBlock block : currentProgram.getMemory().getBlocks()) {
            if (!block.isInitialized() || block.isExecute()) {
                continue;
            }
            for (Map.Entry<String, Set<Address>> entry : strings.entrySet()) {
                byte[] pattern = entry.getKey().getBytes(StandardCharsets.UTF_8);
                byte[] chunk = new byte[1 << 20];
                int overlap = pattern.length - 1;
                long offset = 0;
                while (offset < block.getSize()) {
                    monitor.checkCancelled();
                    int count = (int) Math.min(chunk.length, block.getSize() - offset);
                    currentProgram.getMemory().getBytes(block.getStart().add(offset),
                        chunk, 0, count);
                    for (int i = 0; i <= count - pattern.length; i++) {
                        boolean matches = true;
                        for (int j = 0; j < pattern.length; j++) {
                            if (chunk[i + j] != pattern[j]) {
                                matches = false;
                                break;
                            }
                        }
                        if (matches) {
                            entry.getValue().add(block.getStart().add(offset + i));
                        }
                    }
                    if (count <= overlap) {
                        break;
                    }
                    offset += count - overlap;
                }
            }
        }
    }

    private void scanCode(
            Map<Long, List<Map.Entry<String, Address>>> targetsByPage) throws Exception {
        DecompInterface decompiler = new DecompInterface();
        decompiler.openProgram(currentProgram);
        Set<Address> decompiled = new LinkedHashSet<>();
        byte[] bytes = new byte[1 << 20];
        try {
            for (MemoryBlock block : currentProgram.getMemory().getBlocks()) {
                if (!block.isInitialized() || !block.isExecute()) {
                    continue;
                }
                long offset = 0;
                while (offset + 8 <= block.getSize()) {
                    monitor.checkCancelled();
                    int length = (int) Math.min(bytes.length, block.getSize() - offset);
                    length &= ~3;
                    currentProgram.getMemory().getBytes(block.getStart().add(offset),
                        bytes, 0, length);
                    for (int i = 0; i + 8 <= length; i += 4) {
                        long instruction = word(bytes, i);
                        if ((instruction & 0x9f000000L) != 0x90000000L) {
                            continue;
                        }
                        long immediate = (((instruction >>> 5) & 0x7ffffL) << 2) |
                            ((instruction >>> 29) & 3L);
                        if ((immediate & (1L << 20)) != 0) {
                            immediate |= ~((1L << 21) - 1);
                        }
                        long pc = block.getStart().getOffset() + offset + i;
                        long page = (pc & ~0xfffL) + (immediate << 12);
                        List<Map.Entry<String, Address>> targets = targetsByPage.get(page);
                        if (targets == null) {
                            continue;
                        }
                        int base = (int) (instruction & 0x1f);
                        for (int step = 1; step <= 4 && i + step * 4 < length; step++) {
                            long next = word(bytes, i + step * 4);
                            if ((next & 0x7f000000L) != 0x11000000L ||
                                    ((next >>> 5) & 0x1f) != base) {
                                continue;
                            }
                            long addend = (next >>> 10) & 0xfffL;
                            if (((next >>> 22) & 1) != 0) {
                                addend <<= 12;
                            }
                            long ref = page + addend;
                            for (Map.Entry<String, Address> target : targets) {
                                if (target.getValue().getOffset() != ref) {
                                    continue;
                                }
                                Address reference = block.getStart().add(offset + i + step * 4);
                                Address start = findPrologue(block, offset + i);
                                println("ADRP_ADD_REF\t" + reference + "\t" + target.getKey() +
                                    "\tstart-guess=" + (start == null ? "<not-found>" : start));
                                if (start == null || decompiled.size() >= 30 ||
                                        !decompiled.add(start)) {
                                    continue;
                                }
                                disassemble(start);
                                Function function =
                                    currentProgram.getFunctionManager().getFunctionAt(start);
                                if (function == null) {
                                    function = createFunction(start, "String_ref_" +
                                        Long.toHexString(start.getOffset()));
                                }
                                if (function == null) {
                                    println("DECOMPILE_FAILED\t" + start +
                                        "\tfunction boundary not established");
                                    continue;
                                }
                                DecompileResults result =
                                    decompiler.decompileFunction(function, 45, monitor);
                                if (result.decompileCompleted() &&
                                        result.getDecompiledFunction() != null) {
                                    String code = result.getDecompiledFunction().getC();
                                    println("DECOMPILE_GUESS\t" + start + "\t" +
                                        function.getName(true) + "\n" +
                                        code.substring(0, Math.min(code.length(), 18000)));
                                }
                            }
                            break;
                        }
                    }
                    offset += length;
                }
            }
        }
        finally {
            decompiler.dispose();
        }
    }

    private long word(byte[] bytes, int offset) {
        return (bytes[offset] & 0xffL) |
            ((bytes[offset + 1] & 0xffL) << 8) |
            ((bytes[offset + 2] & 0xffL) << 16) |
            ((bytes[offset + 3] & 0xffL) << 24);
    }

    private Address findPrologue(MemoryBlock block, long instructionOffset) throws Exception {
        long start = Math.max(0, instructionOffset - 0x2000);
        for (long offset = instructionOffset; offset >= start; offset -= 4) {
            long instruction =
                currentProgram.getMemory().getInt(block.getStart().add(offset)) & 0xffffffffL;
            long masked = instruction & 0xffc07fffL;
            if (masked == 0xa9807bfdL || masked == 0xa9007bfdL ||
                    (instruction & 0xffc003ffL) == 0xd10003ffL) {
                return block.getStart().add(offset);
            }
        }
        return null;
    }
}
