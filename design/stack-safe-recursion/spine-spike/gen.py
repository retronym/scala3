# Generates the stress inputs used by run.sh into the directory given as argv[1]
import sys
d = sys.argv[1]
def w(name, body):
    with open(f'{d}/{name}.scala', 'w') as f: f.write(body)
for n in [100, 200, 400, 1000, 3000, 10000]:
    w(f'left{n}', 'object T { def f(x: Int) = x +\n' + ' +\n'.join(str(i) for i in range(n)) + ' }\n')
    w(f'cons{n}', 'object T { val l = ' + ' ::\n'.join(str(i) for i in range(n)) + ' :: Nil }\n')
    w(f'ladder{n}', 'object T { def f(x: Int) =\n' + ' else\n'.join(f'if x == {i} then {i}' for i in range(n)) + ' else -1 }\n')
    w(f'sel{n}', 'object T { val sb = new StringBuilder; def f = sb\n' + ''.join(f'  .append({i})\n' for i in range(n)) + '}\n')
