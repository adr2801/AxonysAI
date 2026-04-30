import numpy as np

def convert_to_kotlin(arr, name):
    res = f"val {name} = arrayOf(\n"
    for row in arr:
        res += "    doubleArrayOf(" + ", ".join(str(x) for x in row) + "),\n"
    res += ")\n"
    return res

try:
    w1 = np.load('weights1maj.npy')
    w2 = np.load('weights2maj.npy')
    b1 = np.load('bias1maj.npy')
    b2 = np.load('bias2maj.npy')
except:
    w1 = np.load('weights1.npy')
    w2 = np.load('weights2.npy')
    b1 = np.load('bias1.npy')
    b2 = np.load('bias2.npy')

with open('kotlin_weights.txt', 'w') as f:
    f.write(convert_to_kotlin(w1, 'w1'))
    f.write(convert_to_kotlin(w2, 'w2'))
    f.write(convert_to_kotlin(b1, 'b1'))
    f.write(convert_to_kotlin(b2, 'b2'))
