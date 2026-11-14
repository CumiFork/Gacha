setTimeout(function() {
    if (window.daduHacked) return;
    window.daduHacked = true;

    let diceCount = 9;
    let targetTotal = 24;
    let plannedValues = [];
    let rollInterval = null;

    function setMode(mode) {
        diceCount = parseInt(document.getElementById('dadu-count').value) || 9;
        
        if (mode === "kecil") {
            let min_possible = diceCount;
            let max_possible = Math.min(30, diceCount * 6);
            if (min_possible > max_possible) return alert("Dadu kebanyakan buat target Kecil!");
            targetTotal = Math.floor(Math.random() * (max_possible - min_possible + 1)) + min_possible;
        } else if (mode === "besar") {
            let min_possible = Math.max(33, diceCount);
            let max_possible = diceCount * 6;
            if (min_possible > max_possible) return alert("Dadu kesedikitan buat target Besar!");
            targetTotal = Math.floor(Math.random() * (max_possible - min_possible + 1)) + min_possible;
        }

        // Bikin kombinasi dadu
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

        // 1. Cari Elemen Total
        let totalEl = null;
        document.querySelectorAll('div, span').forEach(el => {
            if (el.innerText && el.innerText.trim() === 'Total') {
                let parent = el.parentElement;
                if (parent) {
                    Array.from(parent.children).forEach(child => {
                        if (/^\d+$/.test(child.innerText.trim())) totalEl = child;
                    });
                }
            }
        });

        // 2. Cari Elemen Dadu (Pake aria-label Google)
        let diceEls = [];
        document.querySelectorAll('[aria-label^="Die showing"], [aria-label^="Dadu menunjukkan"]').forEach(el => {
            if (el.offsetParent !== null) diceEls.push(el);
        });

        // Fallback kalo aria-label gak nemu
        if (diceEls.length < diceCount) {
            diceEls = [];
            document.querySelectorAll('div, span, text').forEach(el => {
                if (el.offsetParent !== null && /^[1-6]$/.test(el.innerText.trim())) {
                    let fs = parseInt(window.getComputedStyle(el).fontSize);
                    if (fs >= 16 && !el.parentElement.innerText.includes('Total')) {
                        diceEls.push(el);
                    }
                }
            });
        }

        // 3. Timpa Angka Dadu
        if (diceEls.length >= diceCount) {
            for (let i = 0; i < diceCount; i++) {
                if (diceEls[i]) {
                    let val = plannedValues[i].toString();
                    if (diceEls[i].tagName.toLowerCase() === 'text') {
                        diceEls[i].textContent = val;
                    } else {
                        diceEls[i].innerText = val;
                    }
                    if (diceEls[i].hasAttribute('aria-label')) {
                        diceEls[i].setAttribute('aria-label', `Die showing ${val}`);
                    }
                }
            }
        }

        // 4. Timpa Total
        if (totalEl && totalEl.innerText !== targetTotal.toString()) {
            totalEl.innerText = targetTotal;
        }
    }

    // Deteksi Klik Lembar (Pake pointerdown biar cepet)
    document.addEventListener('pointerdown', function(e) {
        let btn = e.target.closest('button') || e.target.parentElement;
        if(btn && btn.innerText && (btn.innerText.includes('Lembar') || btn.innerText.includes('Roll'))) {
            if (plannedValues.length > 0) {
                if (rollInterval) clearInterval(rollInterval);
                
                let loops = 0;
                // Tembak DOM tiap 50 milidetik selama 4 detik penuh!
                rollInterval = setInterval(() => {
                    loops++;
                    if (loops > 80) { 
                        clearInterval(rollInterval);
                        return;
                    }
                    forceDOM();
                }, 50);
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
