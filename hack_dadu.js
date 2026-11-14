setTimeout(function() {
    if (window.daduHacked) {
        alert("Script dadu udah aktif bro!");
        return;
    }
    window.daduHacked = true;

    const originalRandom = Math.random;
    const originalCrypto = window.crypto ? window.crypto.getRandomValues : null;
    
    let targetTotal = 24; 
    let diceCount = 9; 
    let plannedValues = [];
    let seqIndex = 0;
    let isRolling = false;
    let timeoutId = null;

    // Fungsi pembuat kombinasi dadu
    function generatePlan(total, count) {
        if (total < count || total > count * 6) return []; 
        let arr = new Array(count).fill(1);
        let remaining = total - count;
        while (remaining > 0) {
            let idx = Math.floor(originalRandom() * count);
            if (arr[idx] < 6) { arr[idx]++; remaining--; }
        }
        return arr;
    }

    function setMode(mode) {
        let count = parseInt(document.getElementById('dadu-count').value) || 9;
        diceCount = count;
        
        if (mode === "kecil") {
            let min_possible = diceCount;
            let max_possible = Math.min(30, diceCount * 6);
            
            if (min_possible > max_possible) return alert("Dadu kebanyakan buat target Kecil!");
            
            // Random antara min dan max (misal 9 sampai 30)
            targetTotal = Math.floor(originalRandom() * (max_possible - min_possible + 1)) + min_possible;
            
        } else if (mode === "besar") {
            let min_possible = Math.max(33, diceCount);
            let max_possible = diceCount * 6;
            
            if (min_possible > max_possible) return alert("Dadu kesedikitan buat target Besar!");
            
            // Random antara min dan max (misal 33 sampai 54)
            targetTotal = Math.floor(originalRandom() * (max_possible - min_possible + 1)) + min_possible;
            
        } else {
            plannedValues = [];
            document.getElementById('dadu-status').innerText = "Status: Acak Normal";
            return;
        }

        plannedValues = generatePlan(targetTotal, diceCount);
        seqIndex = 0;
        document.getElementById('dadu-status').innerText = `Target: ${targetTotal} (${mode.toUpperCase()})`;
    }

    // --- 1. HOOK MATH.RANDOM PAKE OBJECT.DEFINEPROPERTY (ANTI DI-RESTORE) ---
    Object.defineProperty(Math, 'random', {
        value: function() {
            try {
                if (isRolling && plannedValues.length > 0) {
                    let val = plannedValues[seqIndex];
                    seqIndex = (seqIndex + 1) % plannedValues.length;
                    return (val - 1 + 0.99) / 6; 
                }
            } catch(e) {}
            return originalRandom();
        },
        writable: false,
        configurable: false
    });

    // --- 2. HOOK CRYPTO ---
    if (window.crypto && originalCrypto) {
        Object.defineProperty(window.crypto, 'getRandomValues', {
            value: function(array) {
                try {
                    if (isRolling && plannedValues.length > 0) {
                        for (let i = 0; i < array.length; i++) {
                            let val = plannedValues[seqIndex];
                            seqIndex = (seqIndex + 1) % plannedValues.length;
                            array[i] = Math.floor(((val - 1 + 0.99) / 6) * 256);
                        }
                        return array;
                    }
                } catch(e) {}
                return originalCrypto.apply(window.crypto, arguments);
            },
            writable: false,
            configurable: false
        });
    }

    // --- 3. OVERWRITE DOM PAKE MUTATION OBSERVER ---
    const observer = new MutationObserver(function() {
        if (!isRolling || plannedValues.length === 0) return;
        
        let diceEls = [];
        let totalEl = null;
        
        // Sapu semua elemen teks
        document.querySelectorAll('div, span, text, p').forEach(el => {
            if (el.offsetParent !== null && el.innerText) {
                let text = el.innerText.trim();
                
                // Cari dadu (1-6)
                if (/^[1-6]$/.test(text) && el.children.length === 0) {
                    let fs = parseInt(window.getComputedStyle(el).fontSize);
                    if (fs >= 16) diceEls.push(el);
                }
                
                // Cari Total
                if (text === 'Total') {
                    let parent = el.parentElement;
                    if (parent) {
                        Array.from(parent.children).forEach(child => {
                            if (/^\d+$/.test(child.innerText.trim())) totalEl = child;
                        });
                    }
                }
            }
        });
        
        // Timpa Angka
        if (diceEls.length >= diceCount) {
            for (let i = 0; i < diceCount; i++) {
                if (diceEls[i] && diceEls[i].innerText !== plannedValues[i].toString()) {
                    diceEls[i].innerText = plannedValues[i];
                }
            }
        }
        if (totalEl && totalEl.innerText !== targetTotal.toString()) {
            totalEl.innerText = targetTotal;
        }
    });

    observer.observe(document.body, { childList: true, subtree: true, characterData: true });

    // --- 4. DETEKSI KLIK LEMPAR ---
    document.addEventListener('pointerdown', function(e) {
        let btn = e.target.closest('button') || e.target.parentElement;
        if(btn && btn.innerText && (btn.innerText.includes('Lembar') || btn.innerText.includes('Roll'))) {
            if (plannedValues.length > 0) {
                isRolling = true;
                seqIndex = 0;
                if(timeoutId) clearTimeout(timeoutId);
                timeoutId = setTimeout(() => { isRolling = false; }, 4000); // Kunci 4 detik
            }
        }
    }, true);

    // --- 5. UI TRANSPARAN ---
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
                <button id="btn-kecil" style="flex:1; padding: 6px; background: #4285F4; color: white; border: none; border-radius: 4px; cursor:pointer;">Kecil (<31)</button>
                <button id="btn-besar" style="flex:1; padding: 6px; background: #EA4335; color: white; border: none; border-radius: 4px; cursor:pointer;">Besar (>32)</button>
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
