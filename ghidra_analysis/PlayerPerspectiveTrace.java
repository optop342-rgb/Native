import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.Function;
import ghidra.program.model.mem.MemoryBlock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class PlayerPerspectiveTrace extends GhidraScript {
    private static final Pattern POV = Pattern.compile(
        "(?i)(Camera(FirstPerson|ThirdPerson|PerspectiveOption|RenderFirstPersonObjects|" +
        "FirstPersonClimbing|ThirdPersonBoom|ThirdPersonFixedBoom|SpecialCameraMode)" +
        "|minecraft:camera_(first_person|third_person)|minecraft:(first_person|" +
        "third_person|third_person_front)|playerViewPerspective|key\\.togglePerspective|" +
        "button\\.toggle_perspective|options\\.thirdperson|commands\\.camera\\.perspective|" +
        "camera_third_person_boom|camera_first_person|firstperson_radio|thirdperson_radio|" +
        "variable\\.is_first_person|query\\.is_first_person|FirstPersonPlayerShadowsEnabled|" +
        "THIRD_PERSON)");

    @Override
    public void run() throws Exception {
        Map<Long, String> strings = findStrings();
        println("POV strings found: " + strings.size());
        Map<Long, String> references = findCodeReferences(strings);
        println("Direct code references found: " + references.size());
        decompileReferences(references);
    }

    private Map<Long, String> findStrings() throws Exception {
        Map<Long, String> found = new LinkedHashMap<>();
        for (MemoryBlock block : currentProgram.getMemory().getBlocks()) {
            if (block.isExecute() || !block.isInitialized()) {
                continue;
            }
            StringBuilder value = new StringBuilder();
            long start = block.getStart().getOffset();
            for (long offset = 0; offset < block.getSize(); offset++) {
                if ((offset & 0xfffff) == 0) {
                    monitor.checkCancelled();
                }
                int b = currentProgram.getMemory().getByte(block.getStart().add(offset)) & 0xff;
                if (b >= 0x20 && b <= 0x7e) {
                    if (value.length() < 1024) {
                        value.append((char) b);
                    }
                }
                else {
                    addString(found, start + offset - value.length(), value);
                    value.setLength(0);
                }
            }
        }
        for (Map.Entry<Long, String> entry : found.entrySet()) {
            println("POV_STRING\t" + currentProgram.getAddressFactory()
                .getDefaultAddressSpace().getAddress(entry.getKey()) + "\t" + entry.getValue());
        }
        return found;
    }

    private void addString(Map<Long, String> found, long address, StringBuilder value) {
        if (value.length() >= 5 && POV.matcher(value).find()) {
            found.putIfAbsent(address, value.toString());
        }
    }

    private Map<Long, String> findCodeReferences(Map<Long, String> strings) throws Exception {
        Set<Long> targetPages = new HashSet<>();
        Map<Long, String> pageLabels = new HashMap<>();
        for (Map.Entry<Long, String> entry : strings.entrySet()) {
            long page = entry.getKey() & ~0xfffL;
            targetPages.add(page);
            pageLabels.putIfAbsent(page, entry.getValue());
        }

        Map<Long, String> references = new LinkedHashMap<>();
        byte[] bytes = new byte[1 << 20];
        for (MemoryBlock block : currentProgram.getMemory().getBlocks()) {
            if (!block.isExecute() || !block.isInitialized()) {
                continue;
            }
            long offset = 0;
            while (offset + 16 <= block.getSize()) {
                monitor.checkCancelled();
                int length = (int) Math.min(bytes.length, block.getSize() - offset);
                length &= ~3;
                currentProgram.getMemory().getBytes(block.getStart().add(offset), bytes, 0, length);
                for (int i = 0; i + 12 < length; i += 4) {
                    long adrp = word(bytes, i);
                    if ((adrp & 0x9f000000L) != 0x90000000L) {
                        continue;
                    }
                    long imm = (((adrp >>> 5) & 0x7ffffL) << 2) |
                        ((adrp >>> 29) & 3L);
                    if ((imm & (1L << 20)) != 0) {
                        imm |= ~((1L << 21) - 1);
                    }
                    long pc = block.getStart().getOffset() + offset + i;
                    long page = (pc & ~0xfffL) + (imm << 12);
                    if (!targetPages.contains(page)) {
                        continue;
                    }

                    int base = (int) (adrp & 0x1f);
                    for (int step = 1; step <= 3 && i + step * 4 < length; step++) {
                        long add = word(bytes, i + step * 4);
                        if ((add & 0x7f000000L) != 0x11000000L ||
                                ((add >>> 5) & 0x1f) != base) {
                            continue;
                        }
                        long addend = (add >>> 10) & 0xfffL;
                        if (((add >>> 22) & 1) != 0) {
                            addend <<= 12;
                        }
                        long target = page + addend;
                        String value = strings.get(target);
                        if (value == null) {
                            continue;
                        }
                        long ref = pc + step * 4;
                        references.putIfAbsent(ref, value);
                        println("POV_XREF\t0x" + Long.toHexString(ref) + "\t" + value);
                        break;
                    }
                }
                offset += length;
            }
        }
        return references;
    }

    private void decompileReferences(Map<Long, String> references) throws Exception {
        Map<Long, String> candidates = new LinkedHashMap<>();
        for (Map.Entry<Long, String> entry : references.entrySet()) {
            var refAddress = currentProgram.getAddressFactory()
                .getDefaultAddressSpace().getAddress(entry.getKey());
            Function containing =
                currentProgram.getFunctionManager().getFunctionContaining(refAddress);
            if (containing != null) {
                candidates.putIfAbsent(containing.getEntryPoint().getOffset(), entry.getValue());
                continue;
            }
            var start = findPrologue(refAddress);
            if (start != null) {
                candidates.putIfAbsent(start.getOffset(), entry.getValue());
            }
        }

        DecompInterface decompiler = new DecompInterface();
        decompiler.openProgram(currentProgram);
        int count = 0;
        try {
            for (Map.Entry<Long, String> entry : candidates.entrySet()) {
                if (count >= 16) {
                    break;
                }
                var address = currentProgram.getAddressFactory()
                    .getDefaultAddressSpace().getAddress(entry.getKey());
                Function function = currentProgram.getFunctionManager().getFunctionAt(address);
                if (function == null) {
                    disassemble(address);
                    function = createFunction(address, "POV_candidate_" +
                        Long.toHexString(entry.getKey()));
                }
                if (function == null) {
                    println("DECOMPILE_FAILED\t" + address + "\t" + entry.getValue());
                    continue;
                }
                DecompileResults result = decompiler.decompileFunction(function, 45, monitor);
                if (!result.decompileCompleted() || result.getDecompiledFunction() == null) {
                    println("DECOMPILE_FAILED\t" + address + "\t" + result.getErrorMessage());
                    continue;
                }
                String code = result.getDecompiledFunction().getC();
                println("POV_DECOMPILE\t" + address + "\t" + entry.getValue() + "\n" +
                    code.substring(0, Math.min(code.length(), 18000)));
                count++;
            }
        }
        finally {
            decompiler.dispose();
        }
        println("Candidate functions decompiled: " + count);
    }

    private ghidra.program.model.address.Address findPrologue(
            ghidra.program.model.address.Address reference) throws Exception {
        MemoryBlock block = currentProgram.getMemory().getBlock(reference);
        if (block == null) {
            return null;
        }
        long offset = reference.subtract(block.getStart());
        long min = Math.max(0, offset - 0x4000);
        for (long pos = offset & ~3L; pos >= min; pos -= 4) {
            long ins = currentProgram.getMemory().getInt(block.getStart().add(pos)) & 0xffffffffL;
            long masked = ins & 0xffc07fffL;
            if (masked == 0xa9807bfdL || masked == 0xa9007bfdL ||
                    (ins & 0xffc003ffL) == 0xd10003ffL) {
                return block.getStart().add(pos);
            }
        }
        return null;
    }

    private long word(byte[] bytes, int offset) {
        return (bytes[offset] & 0xffL) |
            ((bytes[offset + 1] & 0xffL) << 8) |
            ((bytes[offset + 2] & 0xffL) << 16) |
            ((bytes[offset + 3] & 0xffL) << 24);
    }
}
