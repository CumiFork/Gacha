setTimeout(function() {
    if (window.daduHacked) return;
    window.daduHacked = true;

    let diceCount = 9;
    let targetTotal = 24;
    let plannedValues = [];
    let isHijacking = false;

    function setMode(mode) {
        diceCount = parseInt(document.getElementById('dadu-count').value) || 9;
        
        if (mode === "kecil") {
            let min_possible = diceCount;
            let max_possible = Math.min(30, diceCount * 6);
            if (min_possible > max_possible) return alert("Dadu kebanyakan buat target Kecil!");
            // Pilih total acak antara min dan max
            targetTotal = Math.floor(Math.random() * (max_possible - min_possible + 1)) + min_possible;
        } else if (mode === "besar") {
            let min_possible = Math.max(33, diceCount);
            let max_possible = diceCount * 6;
            if (min_possible > max_possible) return alert("Dadu kesedikitan buat target Besar!");
            // Pilih total acak antara min dan max
            targetTotal = Math.floor(Math.random() * (max_possible - min_possible + 1)) + min_possible;
        }

        // Bikin kombinasi dadu yang totalnya pas
        let arr = new Array(diceCount).fill(1);
        let remaining = targetTotal - diceCount;
        while (remaining > 0) {
            let idx = Math.floor(Math.random() * diceCount);
            if (arr[idx] < 6) { arr[idx]++; remaining--; }
        }
        
        // Acak urutan biar gak ketauan
        for (let i = arr.length - 1; i > 0; i--) {
            const j = Math.floor(Math.random() * (i + 1));
            [arr[i], arr[j]] = [arr[j], arr[i]];
        }

        plannedValues = arr;
        document.getElementById('dadu-status').innerText = `Target: ${targetTotal} (${mode.toUpperCase()})`;
    }

    function forceDOM() {
        if (plannedValues.length === 0) return;

        let walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
        let node;
        let diceNodes = [];
        let totalNode = null;

        while (node = walker.nextNode()) {
            if (node.parentElement && node.parentElement.offsetParent !== null) {
                let text = node.nodeValue.trim();
                
                // Kumpulin Text Node Dadu
                if (/^[1-6]$/.test(text) && !node.parentElement.closest('button')) {
                    // Cek ukuran font biar gak salah target
                    let fs = parseInt(window.getComputedStyle(node.parentElement).fontSize);
                    if (fs >= 16) diceNodes.push(node);
                }
                
                // Cari Text Node Total
                if (text === 'Total') {
                    let parent = node.parentElement;
                    if (parent && parent.parentElement) {
                        let siblings = parent.parentElement.childNodes;
                        for (let s of siblings) {
                            if (s.nodeType === 3 && /^\d+$/.test(s.nodeValue.trim())) {
                                totalNode = s;
                            } else if (s.nodeType === 1 && /^\d+$/.test(s.innerText.trim())) {
                                totalNode = s.firstChild;
                            }
                        }
                    }
                }
            }
        }

        // Timpa Angka
        if (diceNodes.length >= diceCount) {
            for (let i = 0; i < diceCount; i++) {
                if (diceNodes[i].nodeValue !== plannedValues[i].toString()) {
                    diceNodes[i].nodeValue = plannedValues[i].toString();
                }
            }
        }
        if (totalNode && totalNode.nodeValue !== targetTotal.toString()) {
            totalNode.nodeValue = targetTotal.toString();
        }
    }

    // Deteksi klik tombol Lembar
    document.addEventListener('pointerdown', function(e) {
        let btn = e.target.closest('button') || e.target.parentElement;
        if(btn && btn.innerText && (btn.innerText.includes('Lembar') || btn.innerText.includes('Roll'))) {
            if (plannedValues.length > 0 && !isHijacking) {
                isHijacking = true;
                let startTime = performance.now();
                
                // Pake requestAnimationFrame biar lebih cepet dari React
                function loop() {
                    forceDOM();
                    // Jalanin selama 4 detik (4000ms)
                    if (performance.now() - startTime < 4000) {
                        requestAnimationFrame(loop);
                    } else {
                        isHijacking = false;
                    }
                }
                requestAnimationFrame(loop);
            }
        }
    }, true);

    // --- UI TRANSPARAN ---
    const ui = document.createElement('div');
    ui.id = 'dadu-hack-ui';
    ui.style.cssText = `
        position: fixed; top: 80px; left: 10px; z-index: 999999;
        background: rgba(20, 20, 20, 0.8); backdrop-filter: blur(5px);
        color: #fff; padding: 12px; border-radius: 12px;
        font-family: sans-serif; font-size: 13px;
        box-shadow: 0 4px 12px rgba(0,0,0,0.5);
        border: 1px solid rgba(255,255,255,0.2);
        width: 180px; user-select: none;
    `;

    ui.innerHTML = `
        <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom: 8px; cursor: move;" id="drag-handle">
            <b>🎲 Dadu Hack</b>
            <span id="btn-hide" style="cursor:pointer; color:#ff5555; font-weight:bold; padding: 0 5px;">—</span>
        </div>
        <div id="ui-content">
            <div style="margin-bottom: 8px;">
                <label>Jml Dadu: </label>
                <input type="number" id="dadu-count" value="9" style="width: 40px; background: transparent; color: #fff; border: 1px solid #555; border-radius: 4px; text-align:center;">
            </div>
            <div style="display: flex; gap: 5px; margin-bottom: 8px;">
                <button id="btn-kecil" style="flex:1; padding: 6px; background: #4285F4; color: white; border: none; border-radius: 4px; cursor:pointer;">Kecil</button>
                <button id="btn-besar" style="flex:1; padding: 6px; background: #EA4335; color: white; border: none; border-radius: 4px; cursor:pointer;">Besar</button>
            </div>
            <div id="dadu-status" style="text-align: center; font-size: 11px; color: #00ff00;">Status: Menunggu</div>
        </div>
    `;
    document.body.appendChild(ui);

    let isDragging = false, offsetX, offsetY;
    const dragHandle = document.getElementById('drag-handle');
    dragHandle.onmousedown = (e) => {
        isDragging = true; offsetX = e.clientX - ui.offsetLeft; offsetY = e.clientY - ui.offsetTop;
    };
    document.onmousemove = (e) => {
        if (isDragging) { ui.style.left = (e.clientX - offsetX) + 'px'; ui.style.top = (e.clientY - offsetY) + 'px'; }
    };
    document.onmouseup = () => { isDragging = false; };

    let isHidden = false;
    document.getElementById('btn-hide').onclick = () => {
        isHidden = !isHidden;
        const content = document.getElementById('ui-content');
        if (isHidden) {
            content.style.display = 'none';
            ui.style.opacity = '0.2';
            document.getElementById('btn-hide').innerText = '+';
            document.getElementById('btn-hide').style.color = '#00ff00';
        } else {
            content.style.display = 'block';
            ui.style.opacity = '1';
            document.getElementById('btn-hide').innerText = '—';
            document.getElementById('btn-hide').style.color = '#ff5555';
        }
    };

    document.getElementById('btn-kecil').onclick = () => setMode('kecil');
    document.getElementById('btn-besar').onclick = () => setMode('besar');
}, 2000);
