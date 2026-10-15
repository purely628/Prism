"""calculator：AST 白名单安全求值，拒绝危险表达式。"""

from app.services.agent_core import safe_calculate


def test_basic_arithmetic():
    assert safe_calculate("(12+8)*3").endswith("= 60")
    assert safe_calculate("7/2").endswith("= 3.5")
    assert safe_calculate("2**10").endswith("= 1024")
    assert safe_calculate("10-3-2").endswith("= 5")
    assert safe_calculate("-4*5").endswith("= -20")


def test_reject_import_and_names():
    assert safe_calculate("__import__('os').system('dir')").startswith("错误")
    assert safe_calculate("open('x.txt')").startswith("错误")
    assert safe_calculate("x + 1").startswith("错误")  # 未定义变量（ast.Name 不在白名单）
    assert safe_calculate("[i for i in range(3)]").startswith("错误")
    assert safe_calculate("'abc' + 'd'").startswith("错误")  # 字符串常量不允许


def test_reject_dangerous_literals():
    assert safe_calculate("10**99999").startswith("错误")  # 指数过大
    assert safe_calculate("1/0").startswith("错误")  # 除零
    assert safe_calculate("1//0").startswith("错误")
